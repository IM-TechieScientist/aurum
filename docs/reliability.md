# Reliability and failure recovery

## Atomic writes

Each money operation runs inside one PostgreSQL transaction. The idempotency claim, ledger
transaction, ledger entries, balance updates and audit event either commit together or roll back
together. Clients never observe a partially posted transaction.

PostgreSQL also checks the ledger at commit. A deferred constraint trigger rejects a transaction
unless it contains at least two entries, one currency and equal debit and credit totals.

## Concurrency control

Before posting, Aurum locks every affected account and balance row with `SELECT ... FOR UPDATE`.
UUID sorting gives transfers, reversals and projection rebuilds the same lock order. The funds check
uses the locked balance, so concurrent requests cannot spend the same balance or overwrite one
another's update.

Customer balances cannot become negative. The posting rules apply this check while the projection
row remains locked.

## Idempotency

The `idempotency_record` primary key combines an operation scope with the caller's key. The first
request inserts a claim. PostgreSQL makes a concurrent duplicate wait for that claim to commit or
roll back.

After the first request completes:

- the same key and request hash return the original transaction;
- the same key with a different request hash returns `IDEMPOTENCY_CONFLICT`;
- a rolled-back operation leaves no claim, so the caller can retry safely.

Scopes separate account funding, withdrawals, transfers and reversals. Transfer keys are global
within the transfer scope.

## PostgreSQL retries

Transfers run each attempt in a new transaction and retry only these PostgreSQL failures:

| SQLSTATE | Meaning |
|---|---|
| `40P01` | Deadlock detected |
| `40001` | Serialization failure |

The default is three total attempts. Before a retry, Aurum waits for the configured base delay plus
random jitter from zero through that base delay. The defaults produce a 10–20 ms wait.

Configure the policy with `POSTGRES_RETRY_MAX_ATTEMPTS` and
`POSTGRES_RETRY_BASE_DELAY_MILLIS`. Validation errors, insufficient funds and other database
failures are returned without retrying.

## Reconciliation and projection repair

`GET /api/v1/reconciliation` derives balances from immutable entries and compares them with
`account_balance`. It reports every difference and does not modify data.

The scheduled job runs the same comparison, uses a PostgreSQL advisory lock to coordinate multiple
application instances and stores an immutable report. See [scheduled reconciliation](reconciliation.md).

`POST /api/v1/reconciliation/rebuild` repairs projections in one transaction:

1. Read account IDs in UUID order.
2. Lock each account and balance row in that order.
3. Recompute balances from ledger entries.
4. Update only mismatched projections.
5. Return every previous and rebuilt value.
6. Append an audit event for the authenticated operator.

Posting operations that touch the same accounts wait for these locks. Rebuild never changes ledger
transactions or entries.

## Failure-injection coverage

The runtime `FailureProbe` performs no action during normal requests. Integration tests replace it
with controlled failures at four boundaries:

1. after the transaction header insert;
2. after the ledger entry inserts;
3. immediately before commit;
4. after commit and before the HTTP response.

The first three failures must roll back the transaction, entries, balances, idempotency claim and
audit event. Retrying the same request then creates one transaction. The fourth failure models a
lost response: the committed transaction remains stored, and an exact retry returns it without
moving money again.
