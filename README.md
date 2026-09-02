# Aurum Core

Aurum is a compact, high-integrity transaction ledger built with Java 21, Spring Boot 3,
PostgreSQL, Flyway and Testcontainers. It deliberately focuses on a small money-moving
workflow and makes its correctness properties visible in code and tests.

## What it demonstrates

- Immutable double-entry postings: every transaction has equal debits and credits.
- Atomic funding, transfers, reversals, idempotency records and balance projections.
- Deterministic PostgreSQL row locking to prevent deadlocks and overdrafts.
- Concurrent duplicate-request safety through database-backed idempotency keys.
- Bounded transfer retries for PostgreSQL deadlocks and serialization failures.
- Reversals as compensating entries; posted history is never edited or deleted.
- Reconciliation plus transactionally locked projection rebuilding.
- Scheduled reconciliation with cross-instance locking and immutable mismatch reports.
- Custom transfer and idempotency metrics through Spring Boot Actuator.
- Stateless HTTP Basic authentication with CUSTOMER, OPERATOR, AUDITOR and ADMIN route permissions.
- Reproducible SQL plans and latency measurements over a 100,000-transaction hot-account dataset.
- Real PostgreSQL, MockMvc, contention and jqwik property-based tests.

Amounts are integer minor units. For example, `2500` INR means INR 25.00. Aurum never
uses floating point for money.

## Prerequisites

- Java **21 JDK** (not only the JRE; `javac -version` must report 21)
- Maven 3.6.3+
- Docker with Compose v2

## Run locally

Start only the database:

```bash
docker compose up -d postgres
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. Health is available at
`/actuator/health`.

Local demonstration users are `customer`, `operator`, `auditor` and `admin`; each default
password is `<username>-local`. Override every credential through the `AURUM_*_USERNAME` and
`AURUM_*_PASSWORD` environment variables before using a shared environment.

Run all tests:

```bash
mvn verify
```

The tests start `postgres:16-alpine` automatically and do not require the Compose
database to be running.

## Documentation

- [Documentation index](docs/README.md)
- [Architecture and accounting model](docs/architecture.md)
- [REST API and error contract](docs/api.md)
- [Reliability, locking, retries and projection rebuilds](docs/reliability.md)
- [Testing strategy](docs/testing.md)
- [Metrics and observability](docs/observability.md)
- [Authentication and RBAC](docs/security.md)
- [SQL benchmark and query-plan analysis](docs/benchmarking.md)
- [Scheduled reconciliation and run reports](docs/reconciliation.md)

## Quick demonstration

Create two accounts:

```bash
alice=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"INR"}' | jq -r .id)

bob=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Bob","currency":"INR"}' | jq -r .id)
```

Fund Alice with INR 1,000.00:

```bash
curl -fsS -X POST "localhost:8080/api/v1/accounts/$alice/fund" \
  -u operator:operator-local \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-alice-001' \
  -d '{"amountMinor":100000,"currency":"INR","reference":"demo funding"}'
```

Transfer INR 250.00 to Bob:

```bash
curl -fsS -X POST localhost:8080/api/v1/transfers \
  -u customer:customer-local \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: transfer-demo-001' \
  -d "{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amountMinor\":25000,\"currency\":\"INR\",\"reference\":\"demo transfer\"}"
```

Withdraw INR 100.00 from Bob:

```bash
curl -fsS -X POST "localhost:8080/api/v1/accounts/$bob/withdraw" \
  -u customer:customer-local \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: withdraw-bob-001' \
  -d '{"amountMinor":10000,"currency":"INR","reference":"demo withdrawal"}'
```

Repeating the exact transfer request with the same key returns the original transaction
and does not move money again. Reusing the key with a different payload returns HTTP 409.

Useful reads:

```text
GET /api/v1/accounts/{accountId}
GET /api/v1/accounts/{accountId}/balance
GET /api/v1/accounts/{accountId}/transactions?limit=20&before={transactionId}
GET /api/v1/transactions/{transactionId}
GET /api/v1/reconciliation
GET /api/v1/reconciliation/runs?limit=20
POST /api/v1/reconciliation/rebuild
```

Mutation endpoints:

```text
PATCH /api/v1/accounts/{accountId}/freeze
PATCH /api/v1/accounts/{accountId}/unfreeze
POST  /api/v1/transactions/{transactionId}/reversal
```

## Accounting model

Customer accounts are liabilities with a normal credit balance. Aurum seeds one debit-normal
settlement asset account for INR and USD. Funding debits settlement and credits the customer;
a transfer debits the source customer and credits the destination customer; a withdrawal
debits the customer and credits settlement.

`account_balance` is a transactionally updated projection. `ledger_entry` remains the source
of truth. `GET /api/v1/reconciliation` recomputes balances and reports any mismatch.
The internal/demo rebuild endpoint repairs mismatched projections while holding deterministic
account locks; see the [reliability guide](docs/reliability.md) before exposing it.

## Storage hygiene

The source tree is tiny. Generated classes and the executable JAR live under ignored `target/`.
Remove them with `mvn clean`. Compose creates one named database volume; remove only this
project's containers and data with `docker compose down --volumes`. Both development and tests
reuse `postgres:16-alpine`, avoiding a duplicate PostgreSQL image.

The database and HTTP Basic credentials documented here are development-only. See the
[security guide](docs/security.md) for the permission matrix and production limitations.
