# Architecture and accounting

## Scope

Aurum is a modular Spring Boot monolith. PostgreSQL is the authority for concurrency and
durability; there is no cache, message broker or second datastore. Accounts have one currency,
and Aurum performs no foreign-exchange conversion.

## Modules

| Package | Responsibility |
|---|---|
| `account` | Account creation, status changes, balance reads and row locking |
| `ledger` | Money operations, balanced posting, immutable entries and history |
| `idempotency` | Atomic request claims, request-hash comparison and replay lookup |
| `reconciliation` | Ledger-to-projection comparison and controlled rebuilding |
| `observability` | Fixed-cardinality business metrics |
| `security` | Stateless authentication, endpoint RBAC and security error responses |
| `common` | Stable API errors, hashing and PostgreSQL retry classification |

Controllers translate HTTP contracts into service calls. Only the posting service creates ledger
transactions and entries. Repository code owns SQL, while Flyway owns the schema and database
integrity triggers.

## Accounting model

Customer accounts are credit-normal liabilities. Settlement accounts are debit-normal assets.
Every operation posts equal positive debit and credit amounts in one currency.

| Operation | Debit | Credit |
|---|---|---|
| Funding | Settlement | Customer |
| Withdrawal | Customer | Settlement |
| Transfer | Source customer | Destination customer |
| Reversal | Opposite of each original entry | Opposite of each original entry |

Amounts are signed only by accounting direction. The stored `amount_minor` is always positive;
INR `2500` represents INR 25.00. Floating point is never used.

## Source of truth and projection

`ledger_transaction` and `ledger_entry` are append-only. PostgreSQL triggers reject updates and
deletes, and a deferred constraint trigger rejects transactions that are not balanced at commit.

`account_balance` is a lockable, transactionally maintained projection used for fast reads and
funds checks. It can be independently recomputed from immutable entries. A projection mismatch is
therefore detectable and repairable without rewriting financial history.

## Money-operation flow

1. Validate and normalize the request.
2. Claim the scoped idempotency key.
3. Lock every affected account and balance row in UUID order.
4. Validate account type, state, currency and available funds.
5. Insert one transaction and its balanced entries.
6. Update all affected balance projections.
7. Attach the transaction ID to the idempotency claim.
8. Commit once and return the stored transaction.

All database changes in steps 2–7 share one PostgreSQL transaction.
