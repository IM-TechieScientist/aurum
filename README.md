# Aurum Core

Aurum is a compact, high-integrity transaction ledger built with Java 21, Spring Boot 3,
PostgreSQL, Flyway and Testcontainers. It deliberately focuses on a small money-moving
workflow and makes its correctness properties visible in code and tests.

## What it demonstrates

- Immutable double-entry postings: every transaction has equal debits and credits.
- Atomic funding, transfers, reversals, idempotency records and balance projections.
- Deterministic PostgreSQL row locking to prevent deadlocks and overdrafts.
- Concurrent duplicate-request safety through database-backed idempotency keys.
- Reversals as compensating entries; posted history is never edited or deleted.
- Reconciliation of projected balances against the ledger.
- Real PostgreSQL integration and contention tests with Testcontainers.

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

Run all tests:

```bash
mvn verify
```

The tests start `postgres:16-alpine` automatically and do not require the Compose
database to be running.

## Quick demonstration

Create two accounts:

```bash
alice=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"INR"}' | jq -r .id)

bob=$(curl -fsS -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Bob","currency":"INR"}' | jq -r .id)
```

Fund Alice with INR 1,000.00:

```bash
curl -fsS -X POST "localhost:8080/api/v1/accounts/$alice/fund" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-alice-001' \
  -d '{"amountMinor":100000,"currency":"INR","reference":"demo funding"}'
```

Transfer INR 250.00 to Bob:

```bash
curl -fsS -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: transfer-demo-001' \
  -d "{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amountMinor\":25000,\"currency\":\"INR\",\"reference\":\"demo transfer\"}"
```

Withdraw INR 100.00 from Bob:

```bash
curl -fsS -X POST "localhost:8080/api/v1/accounts/$bob/withdraw" \
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

## Storage hygiene

The source tree is tiny. Generated classes and the executable JAR live under ignored `target/`.
Remove them with `mvn clean`. Compose creates one named database volume; remove only this
project's containers and data with `docker compose down --volumes`. Both development and tests
reuse `postgres:16-alpine`, avoiding a duplicate PostgreSQL image.

The credentials in `compose.yaml` are development-only. Authentication and authorization are
intentionally outside Aurum Core's scope.
