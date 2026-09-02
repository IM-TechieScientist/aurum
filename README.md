# Aurum

Aurum is a transaction ledger API built with Java 21, Spring Boot and PostgreSQL. It manages
customer accounts, funding, withdrawals, transfers and reversals through an immutable double-entry
ledger. Database constraints, row locking, idempotency records and reconciliation protect the
ledger when requests are concurrent, retried or interrupted.

I built Aurum to learn how financial backends apply double-entry accounting, ACID transactions,
concurrency control, idempotent API design, role-based access control (RBAC), audit trails, failure
recovery, database benchmarking and container-based delivery.

## Key capabilities

### Ledger correctness

- Every transaction contains balanced debit and credit entries in one currency.
- Ledger transactions and entries are append-only; corrections create linked reversal transactions
  with compensating entries.
- Amounts use integer minor units, so INR `2500` represents INR 25.00.
- PostgreSQL commits the ledger entries, balance projection, idempotency result and audit event in
  one transaction.
- Database triggers reject ledger mutations and unbalanced postings.

### Reliability and security

- Deterministic `SELECT ... FOR UPDATE` locking prevents lost updates and overspending.
- Database-backed idempotency returns the original transaction for an exact retry and rejects a
  reused key with a different payload.
- Transfers retry PostgreSQL deadlocks and serialization failures with bounded jitter.
- CUSTOMER, OPERATOR, AUDITOR and ADMIN roles control routes and account-level access.
- Accounts have durable owners and support ACTIVE, FROZEN and irreversible CLOSED states.
- Actor-attributed audit events record sensitive user, account, ledger and rebuild operations.

### Verification and delivery

- Scheduled reconciliation compares balance projections with balances derived from the ledger and
  stores immutable run reports.
- JUnit, MockMvc, Testcontainers and jqwik cover HTTP contracts, PostgreSQL constraints,
  concurrency, rollback, failure injection and generated operation sequences.
- SQL and HTTP benchmark harnesses report query plans, throughput and latency percentiles.
- Spring Boot Actuator exposes health checks and fixed-cardinality ledger metrics.
- GitHub Actions verifies every push and pull request and publishes tagged images to GHCR.

## Technology

| Area | Technology |
|---|---|
| Application | Java 21, Spring Boot 3.5, Spring MVC, Spring JDBC |
| Data | PostgreSQL 16, Flyway |
| Security | Spring Security, HTTP Basic, PBKDF2 password hashing |
| Testing | JUnit 5, MockMvc, Testcontainers, jqwik |
| Observability | Spring Boot Actuator, Micrometer |
| Delivery | Maven, Docker, Docker Compose, GitHub Actions, GHCR |

## Run locally

### Prerequisites

- Java 21 JDK
- Maven 3.6.3 or newer
- Docker with Compose v2
- `jq` for the command-line walkthrough below

Start PostgreSQL and the application:

```bash
docker compose up -d postgres
mvn spring-boot:run
```

The API is available at `http://localhost:8080`; health is available at
`http://localhost:8080/actuator/health`. Flyway applies the schema migrations during startup.

The local configuration creates four bootstrap users:

| Username | Password | Role |
|---|---|---|
| `customer` | `customer-local` | CUSTOMER |
| `operator` | `operator-local` | OPERATOR |
| `auditor` | `auditor-local` | AUDITOR |
| `admin` | `admin-local` | ADMIN |

These credentials are for local use. Configure different usernames and passwords through the
`AURUM_*_USERNAME` and `AURUM_*_PASSWORD` environment variables for a shared environment.

## API walkthrough

Create two INR accounts owned by the bootstrap customer:

```bash
alice=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","ownerUsername":"customer","currency":"INR"}' | jq -r .id)

bob=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Bob","ownerUsername":"customer","currency":"INR"}' | jq -r .id)
```

Fund Alice with INR 1,000.00:

```bash
curl -fsS -X POST "localhost:8080/api/v1/accounts/$alice/fund" \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-alice-001' \
  -d '{"amountMinor":100000,"currency":"INR","reference":"initial funding"}'
```

Transfer INR 250.00 from Alice to Bob:

```bash
curl -fsS -X POST localhost:8080/api/v1/transfers \
  -u customer:customer-local \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: transfer-alice-bob-001' \
  -d "{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amountMinor\":25000,\"currency\":\"INR\",\"reference\":\"account transfer\"}"
```

Read both balances and verify the projections against the ledger:

```bash
curl -fsS -u customer:customer-local \
  "localhost:8080/api/v1/accounts/$alice/balance"
curl -fsS -u customer:customer-local \
  "localhost:8080/api/v1/accounts/$bob/balance"
curl -fsS -u auditor:auditor-local \
  localhost:8080/api/v1/reconciliation
```

Sending the same transfer again with the same key and payload returns the original transaction.
Changing the payload while reusing the key returns HTTP 409.

## API overview

| Area | Operations |
|---|---|
| Accounts | Create, read balance, freeze, unfreeze and close |
| Ledger | Fund, withdraw, transfer, reverse and read history |
| Reconciliation | Compare projections, inspect run reports and rebuild projections |
| Administration | Create users, change roles and read audit events |
| Operations | Health, application info and Micrometer metrics |

See the [REST API guide](docs/api.md) for request fields, access rules and error codes.

## Tests and benchmarks

Run the standard verification suite:

```bash
mvn verify
```

The suite starts PostgreSQL 16 through Testcontainers and covers the application through real SQL
and HTTP paths. The two larger benchmarks run separately:

```bash
mvn -Dtest=SqlBenchmark test
mvn -Dtest=HttpLoadBenchmark test
```

The SQL harness builds a 100,000-transaction fixture and captures `EXPLAIN ANALYZE` plans. Its
reference optimization run reduced median hot-account history latency from 145.875 ms to 1.401 ms.
Benchmark method and context are documented in [SQL benchmarking](docs/benchmarking.md) and
[HTTP load testing](docs/load-testing.md).

## Container image

Build the application and local image:

```bash
mvn verify
docker build -t aurum:local .
```

The image runs the Java process as user `10001` and expects PostgreSQL connection settings through
environment variables. Pushing a version tag such as `v1.0.0`, or manually starting the publish
workflow, builds and publishes `ghcr.io/<owner>/<repository>` with provenance and an SBOM.

## Documentation

- [Architecture and accounting model](docs/architecture.md)
- [REST API](docs/api.md)
- [Reliability and failure recovery](docs/reliability.md)
- [Authentication and authorization](docs/security.md)
- [Audit events](docs/audit.md)
- [Reconciliation](docs/reconciliation.md)
- [Metrics and observability](docs/observability.md)
- [Testing strategy](docs/testing.md)
- [SQL benchmarking](docs/benchmarking.md)
- [HTTP load testing](docs/load-testing.md)
- [Operations and delivery](docs/operations.md)

The [documentation index](docs/README.md) provides a guided reading order.

## Storage cleanup

Generated classes, reports and JAR files are written under the ignored `target/` directory. Remove
them with `mvn clean`. Stop the local database with `docker compose down`; add `--volumes` only when
you also want to delete the local Aurum database.
