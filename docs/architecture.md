# Architecture and accounting

## System overview

Aurum is a modular Spring Boot application backed by PostgreSQL. The API, business logic and data
access code run in one process, while PostgreSQL provides durable storage, transactions, row locks
and integrity constraints. Flyway owns every schema change.

Accounts hold one currency. The current schema provides INR and USD settlement accounts and does
not perform foreign-exchange conversion.

## Application modules

| Package | Responsibility |
|---|---|
| `account` | Customer accounts, ownership, lifecycle state, balances and row locks |
| `ledger` | Funding, withdrawals, transfers, reversals and transaction history |
| `idempotency` | Request claims, payload hashes and stored-result replay |
| `reconciliation` | Ledger-derived balance checks, run reports and projection repair |
| `security` | Users, password hashing, authentication, roles and resource ownership |
| `audit` | Actor-attributed records for sensitive operations |
| `observability` | Transfer, retry, idempotency and reconciliation metrics |
| `reliability` | Controlled failure points used by integration tests |
| `common` | API errors, request hashing and PostgreSQL retry classification |

Controllers validate HTTP input and apply resource-level authorization. Services define the
transaction boundaries and business rules. Repositories contain SQL. The posting service is the
only application component that inserts ledger transactions and entries.

## Double-entry model

Customer accounts are credit-normal liabilities. Settlement accounts are debit-normal assets.
Every operation creates equal positive debit and credit amounts in one currency.

| Operation | Debit | Credit |
|---|---|---|
| Funding | Settlement account | Customer account |
| Withdrawal | Customer account | Settlement account |
| Transfer | Source customer account | Destination customer account |
| Reversal | Opposite side of each original entry | Opposite side of each original entry |

`amount_minor` stores integer minor units and is always positive. Entry direction determines
whether the amount increases or decreases an account according to its normal side. Floating-point
arithmetic is not used for money.

## Source of truth and balance projection

`ledger_transaction` and `ledger_entry` are the financial source of truth. PostgreSQL triggers
reject updates and deletes, and a deferred constraint trigger verifies at commit that each new
transaction:

- contains at least two entries;
- uses one currency;
- has equal debit and credit totals.

`account_balance` stores the current projected balance for fast reads and funds checks. The same
database transaction writes the immutable entries and updates the projection. Reconciliation can
independently recompute every balance from the ledger, detect a difference and repair only the
projection without changing financial history.

## Identity, ownership and audit data

Each customer account references a durable `app_user` with the CUSTOMER role. CUSTOMER requests
can read their own accounts and transactions that touch those accounts. A withdrawal or transfer
also requires ownership of the source account. Settlement accounts have no user owner.

Account states are ACTIVE, FROZEN and CLOSED. A frozen account can receive money but cannot send or
withdraw it. Closing requires a locked zero balance, is irreversible and prevents later postings
that involve the account.

`audit_event` stores the actor, action, target, correlation value and timestamp for successful
security-sensitive operations. Audit insertion shares the business transaction, so rolled-back
operations do not leave success records. Database triggers reject audit updates and deletes.

## Posting transaction

Each money request follows the same path:

1. Validate the amount, currency, account identifiers and reference.
2. Claim the scoped idempotency key and compare the request hash.
3. Lock every affected account and balance row in UUID order.
4. Check account type, state, currency and available funds.
5. Insert the transaction header and balanced ledger entries.
6. Update the affected balance projections.
7. Complete the idempotency record and append the audit event.
8. Commit once and return the stored transaction.

A failure before commit rolls back every database change from this sequence.
