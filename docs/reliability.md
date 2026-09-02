# Reliability model

## Atomicity and locking

Each posting, its balance updates and its idempotency result commit in one PostgreSQL transaction.
Affected account and projection rows are locked with `SELECT ... FOR UPDATE`. UUID sorting gives
transfers, reversals and rebuilds a common deterministic lock order, reducing deadlock risk.

Customer projections may never become negative. The funds check uses the locked projection, so
concurrent operations cannot both spend the same balance.

## Idempotency

The primary key `(scope, idempotency_key)` admits one owner. `INSERT ... ON CONFLICT DO NOTHING`
waits on a competing uncommitted claim when necessary. After the owner commits:

- an identical request hash receives the original transaction;
- a different hash receives `IDEMPOTENCY_CONFLICT`;
- a failed transaction rolls back its claim, allowing a safe later attempt.

Scopes prevent unrelated routes from colliding while ensuring transfer keys are globally unique
within the transfer operation.

## PostgreSQL retries

The public transfer operation executes each attempt in a fresh transaction. It retries only:

| SQLSTATE | Meaning |
|---|---|
| `40P01` | Deadlock detected |
| `40001` | Serialization failure |

The default is three total attempts with 10–20 ms jitter before each retry. Configure it with
`POSTGRES_RETRY_MAX_ATTEMPTS` and `POSTGRES_RETRY_BASE_DELAY_MILLIS`. Validation, insufficient
funds, unique violations and other failures are never retried. Retrying the whole transaction is
safe because any idempotency claim from an aborted attempt rolls back with it.

## Reconciliation

`GET /api/v1/reconciliation` derives every account balance from immutable entries and compares it
with `account_balance`. It is read-only and reports every mismatch.

The scheduled job executes the same comparison and stores an immutable report. A PostgreSQL
transaction-scoped advisory lock permits only one application instance to execute a run at a time;
contending instances skip instead of duplicating work. See [scheduled reconciliation](reconciliation.md).

## Projection rebuild

`POST /api/v1/reconciliation/rebuild` performs these steps in one transaction:

1. Snapshot account IDs in UUID order.
2. Lock each account and balance row in that order.
3. Recompute ledger-derived balances.
4. Update only mismatched projections.
5. Return scanned-account and repair details.

Money operations that overlap the rebuild wait on the same locks. The endpoint never edits ledger
transactions or entries. Automatic silent repair is intentionally avoided; an operator receives a
specific report of what changed.

The rebuild route requires OPERATOR or ADMIN. A production deployment should additionally isolate
management traffic and add a durable operator audit trail.
