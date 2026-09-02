# Testing strategy

Aurum tests financial rules at the API, service and database layers. PostgreSQL integration tests
use the same database engine, constraints and locking behavior as the running application.

## Standard verification

```bash
mvn verify
```

The current suite reports 26 passing tests. Testcontainers starts PostgreSQL 16 automatically, so
the Compose database does not need to be running. GitHub Actions executes the same command for every
push and pull request.

## Verification layers

### Ledger and database integration

Integration tests cover funding, withdrawals, transfers, insufficient funds, currency checks,
account states, transaction history, reversals, idempotent replay, projection rebuilding and
reconciliation. Direct SQL tests also verify that PostgreSQL rejects unbalanced transactions and
updates or deletes against immutable ledger and audit tables.

### HTTP contracts and authorization

MockMvc sends requests through Spring Security, request validation, controllers, services and
PostgreSQL. These tests verify:

- successful HTTP 201 transaction responses and exact replay;
- structured `application/problem+json` errors;
- public health and protected API routes;
- role restrictions for CUSTOMER, OPERATOR, AUDITOR and ADMIN;
- customer ownership checks and cross-customer resource hiding;
- account closure, user administration, audit access and metric visibility.

### Concurrent requests

Two tests exercise database behavior under contention:

- Twenty transfers compete for one funded source account. Only the affordable transfers succeed,
  and the source never becomes negative.
- Twenty requests use the same idempotency key concurrently. Every successful caller receives the
  same transaction ID, and balances move once.

### Property-based operation sequences

jqwik generates 20 sequences containing funding, withdrawals, transfers, freezes, unfreezes and
reversals. After each operation, the test checks:

- debit totals equal credit totals;
- each transaction contains at least two entries and one currency;
- customer balances are non-negative;
- stored projections match an independent test model;
- full reconciliation reports no mismatch.

Each generated sequence writes immutable history to the shared test database, so shrinking is
disabled and every try starts from newly created accounts.

### PostgreSQL retry classification

Focused unit tests verify that transfer execution retries SQLSTATE `40P01` and `40001`, stops at the
configured attempt limit and does not retry domain failures or unrelated SQL errors.

### Controlled failures

Integration tests inject failures after the transaction insert, after entry inserts, before commit
and after commit but before the HTTP response. They verify complete rollback before commit and
idempotent recovery when a client loses a successful response.

### Scheduled reconciliation

The clock-driven trigger is disabled in tests. The suite invokes the same reconciliation job
directly and verifies consistent and mismatched reports, immutable details, metrics, access rules
and advisory-lock skips.

## Performance harnesses

The SQL and HTTP benchmarks are separate from the standard verification suite because they create
larger fixtures and measure machine-dependent latency.

```bash
mvn -Dtest=SqlBenchmark test
mvn -Dtest=HttpLoadBenchmark test
```

Both commands fail on correctness errors and write reports under `target/benchmarks/`. See
[SQL benchmarking](benchmarking.md) and [HTTP load testing](load-testing.md) for their workloads and
reference results.

## Storage-conscious test run

Generated output stays under the ignored `target/` directory. To keep Maven dependencies outside
the normal local cache for a one-off run, use a temporary repository:

```bash
mvn -Dmaven.repo.local=/tmp/aurum-m2 verify
mvn clean
```

Remove `/tmp/aurum-m2` after the run if the dependencies are no longer needed.
