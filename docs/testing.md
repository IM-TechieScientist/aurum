# Testing strategy

Run everything with:

```bash
mvn verify
```

The existing GitHub Actions workflow runs this command on every push and pull request. It therefore
provides compilation, automated tests and application packaging in CI. Static analysis, container
publishing and deployment are separate future pipeline stages, not prerequisites for the test suite.

All persistence and HTTP integration tests use real PostgreSQL through Testcontainers. H2 is not
used because its locking, transaction and SQL behavior would not prove Aurum's main guarantees.

## Verification layers

### PostgreSQL integration tests

These cover successful and rejected money operations, rollback, immutable entries, deferred
balance constraints, reversals, history, projection rebuilding and reconciliation.

### Concurrency tests

- Twenty transfers compete for one funded source; exactly the affordable number succeeds.
- Twenty identical requests share one idempotency key; every caller receives one transaction ID
  and balances move once.

### MockMvc contract tests

HTTP tests exercise the complete controller, validation, service and database path. They verify
HTTP status codes, transaction JSON, exact replay, RFC 9457-style Problem Details and Actuator
metric visibility. Security contract tests also verify public health access, JSON 401/403
responses, CUSTOMER denial on operator operations and AUDITOR reconciliation access.

### Property-based tests

jqwik generates sequences containing funding, withdrawals, bidirectional transfers, account
freezes/unfreezes and reversals. After every generated operation, the suite asserts:

- all transaction debit totals equal credit totals;
- every transaction has at least two entries and one currency;
- no customer projection is negative;
- application balances match an independent test model;
- reconciliation reports no ledger/projection mismatch.

The property suite boots the actual application against PostgreSQL. It uses 20 generated
sequences per build and disables shrinking because each attempt intentionally leaves an immutable
audit trail in the shared test database.

### Retry classification tests

Focused tests prove that deadlocks and serialization failures retry up to the configured bound,
while business failures and unrelated SQLSTATE values execute once.

### Opt-in SQL benchmark

`SqlBenchmark` generates a large ephemeral ledger and measures indexed balance/history queries plus
full reconciliation. It is deliberately excluded from routine `mvn verify`; see the
[benchmarking guide](benchmarking.md) for the command and methodology.

### Scheduled reconciliation tests

The clock-driven trigger is disabled during tests. Integration tests invoke the same job directly
and verify consistent and mismatched reports, immutable mismatch details, bounded metrics, secured
history access and advisory-lock skips.

## Storage-conscious local runs

Generated output lives under ignored `target/`. To avoid retaining a Maven cache for an isolated
verification, use a temporary repository and remove it afterward:

```bash
mvn -Dmaven.repo.local=/tmp/aurum-m2 verify
mvn clean
```

The PostgreSQL image is reusable. Testcontainers' small Ryuk helper image can be removed between
runs if minimizing disk use is more important than avoiding the next download.
