# Scheduled reconciliation

## Purpose

Reconciliation independently derives account balances from immutable ledger entries and compares
them with the fast `account_balance` projection. The live endpoint remains available, while the
scheduled job creates durable evidence that the check ran and records every mismatch it observed.

## Schedule

The job runs hourly in UTC by default. Configure it without rebuilding the application:

| Environment variable | Default | Purpose |
|---|---|---|
| `RECONCILIATION_SCHEDULE_ENABLED` | `true` | Enable or disable the clock-driven trigger |
| `RECONCILIATION_SCHEDULE_CRON` | `0 0 * * * *` | Spring six-field cron expression |
| `RECONCILIATION_SCHEDULE_ZONE` | `UTC` | Time zone used to interpret the cron expression |

The trigger delegates to `ReconciliationJob`, so deterministic tests and future operator tooling
can execute exactly the same path without waiting for the clock.

## Cross-instance coordination

Each attempt starts a PostgreSQL transaction and calls `pg_try_advisory_xact_lock` with Aurum's
fixed reconciliation lock key. One application instance acquires the lock; simultaneous attempts
on other instances return immediately as `skipped`. PostgreSQL releases the lock automatically at
transaction end, including rollback or connection failure, so no cleanup lease is required.

This coordinates Aurum instances that share the same database. It intentionally does not block the
live read-only endpoint or the operator-controlled projection rebuild.

## Durable reports

An executed check appends one `reconciliation_run` row and zero or more
`reconciliation_run_mismatch` rows in the same transaction. Database triggers reject updates and
deletes from both tables. A report contains:

- consistent or mismatched status;
- number of accounts scanned and mismatches found;
- start and completion timestamps;
- each affected account, currency, projected balance and ledger-derived balance.

Read the newest reports with an AUDITOR, OPERATOR or ADMIN identity:

```bash
curl -fsS -u auditor:auditor-local \
  'localhost:8080/api/v1/reconciliation/runs?limit=20'
```

The endpoint accepts a limit from 1 through 50. Reports diagnose inconsistencies but never repair
them. An OPERATOR or ADMIN must separately invoke `POST /api/v1/reconciliation/rebuild` after
investigating the cause.

The hourly default appends 8,760 summary rows per year, plus detail rows only when mismatches exist.
No automatic purge is performed because the reports are audit evidence. For a long-lived local
database, reduce the frequency or disable scheduling when continuous evidence is unnecessary.

## Failure visibility

Every attempt records a bounded outcome and duration metric. Completed runs update the last-known
mismatch gauge. Failures are logged and counted as `error`; lock contention is counted as
`skipped`. A database failure rolls back the report, so a stored run is always complete.
