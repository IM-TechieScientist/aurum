# Scheduled reconciliation

## Purpose

The ledger is the financial source of truth, while `account_balance` provides fast balance reads.
Reconciliation derives each balance from immutable ledger entries and compares it with the stored
projection. This verifies that projection updates remain consistent with financial history.

## Schedule and configuration

The scheduled check runs hourly in UTC by default:

| Environment variable | Default | Description |
|---|---|---|
| `RECONCILIATION_SCHEDULE_ENABLED` | `true` | Enables the scheduled trigger |
| `RECONCILIATION_SCHEDULE_CRON` | `0 0 * * * *` | Spring six-field cron expression |
| `RECONCILIATION_SCHEDULE_ZONE` | `UTC` | Time zone used for the cron expression |

The scheduler calls the same `ReconciliationJob` used by integration tests, keeping scheduling,
metrics and persistence behavior on one execution path.

## Coordination across instances

Every scheduled attempt opens a transaction and calls `pg_try_advisory_xact_lock` with Aurum's
fixed reconciliation lock key. One application instance acquires the lock. Other instances record
a `skipped` metric and return without running a duplicate scan.

PostgreSQL releases the transaction-scoped lock at commit or rollback, including connection
failure. The lock coordinates instances connected to the same database and does not block the live
read endpoint or the operator-controlled projection rebuild.

## Immutable run reports

An executed check stores one `reconciliation_run` row and any mismatch details in the same
transaction. A report contains:

- CONSISTENT or MISMATCHED status;
- the number of accounts scanned and mismatches found;
- start and completion timestamps;
- account ID, currency, projected balance and ledger-derived balance for each mismatch.

Database triggers reject updates and deletes on both reconciliation report tables.

OPERATOR, AUDITOR and ADMIN users can read recent reports:

```bash
curl -fsS -u auditor:auditor-local \
  'localhost:8080/api/v1/reconciliation/runs?limit=20'
```

`limit` accepts values from 1 through 50. Reports diagnose differences but do not repair them.
OPERATOR and ADMIN users can run `POST /api/v1/reconciliation/rebuild` after reviewing the result.
The rebuild locks affected data, repairs the projection and creates an audit event.

## Metrics and failures

Each attempt records its duration and one bounded outcome: `consistent`, `mismatched`, `skipped` or
`error`. Completed runs update the last-known mismatch gauge. An exception is logged, counted and
allowed to roll back the report transaction; the scheduler remains active for the next run.

Run reports have no automatic purge. At the hourly default, a continuously running instance writes
8,760 summary rows per year, plus detail rows for detected mismatches.
