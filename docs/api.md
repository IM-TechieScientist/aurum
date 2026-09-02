# REST API

All application routes use the `/api/v1` prefix and JSON request/response bodies. Authentication is
HTTP Basic. The [security guide](security.md) contains the complete role matrix.

## Request rules

- Money amounts are integer minor units between 1 and `9,000,000,000,000,000`.
- Currency values contain three letters and are normalized to uppercase.
- Supported account currencies are INR and USD.
- References and reversal reasons are limited to 200 characters.
- Every money-changing request requires an `Idempotency-Key` header containing 1–128 characters.
- An exact replay returns the original transaction. Reusing the key for a different payload returns
  HTTP 409.

## Accounts

| Method and path | Access | Description |
|---|---|---|
| `POST /api/v1/accounts` | OPERATOR, ADMIN | Create a customer account |
| `GET /api/v1/accounts/{accountId}` | Authenticated | Read account details and projected balance |
| `GET /api/v1/accounts/{accountId}/balance` | Authenticated | Read account ID, currency and balance |
| `PATCH /api/v1/accounts/{accountId}/freeze` | OPERATOR, ADMIN | Block outgoing money operations |
| `PATCH /api/v1/accounts/{accountId}/unfreeze` | OPERATOR, ADMIN | Restore outgoing money operations |
| `PATCH /api/v1/accounts/{accountId}/close` | OPERATOR, ADMIN | Permanently close a zero-balance account |

Create an account with an enabled CUSTOMER owner:

```json
{
  "ownerName": "Alice",
  "ownerUsername": "customer",
  "currency": "INR"
}
```

The response includes the account ID, owner identity, currency, type, normal accounting side,
status, projected balance and creation timestamp.

A frozen account can receive funding and transfers but cannot withdraw or send a transfer. A
closed account cannot be reopened and cannot participate in funding, withdrawals, transfers or
reversals.

CUSTOMER users can read only accounts they own. OPERATOR, AUDITOR and ADMIN users can read every
account.

## Money operations

| Method and path | Access | Request body |
|---|---|---|
| `POST /api/v1/accounts/{accountId}/fund` | OPERATOR, ADMIN | `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/accounts/{accountId}/withdraw` | CUSTOMER owner, OPERATOR, ADMIN | `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/transfers` | CUSTOMER source owner, OPERATOR, ADMIN | `sourceAccountId`, `destinationAccountId`, `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/transactions/{transactionId}/reversal` | OPERATOR, ADMIN | `reason` |

Successful money operations return HTTP 201 and a transaction containing:

- transaction ID, type, reference and creation timestamp;
- `reversalOf` when the transaction reverses another transaction;
- the complete list of debit and credit entries.

Only one reversal may reference an original transaction. A reversal transaction cannot itself be
reversed.

## Ledger queries

| Method and path | Access | Description |
|---|---|---|
| `GET /api/v1/transactions/{transactionId}` | Authenticated | Read a transaction and all entries |
| `GET /api/v1/accounts/{accountId}/transactions` | Authenticated | Read newest-first account history |

Account history accepts `limit` from 1 through 50 and an optional exclusive transaction cursor:

```text
GET /api/v1/accounts/{accountId}/transactions?limit=20&before={transactionId}
```

CUSTOMER users can read transactions that include at least one account they own. Other authorized
roles can read all transactions.

## Reconciliation

| Method and path | Access | Description |
|---|---|---|
| `GET /api/v1/reconciliation` | OPERATOR, AUDITOR, ADMIN | Compare all projected balances with the ledger |
| `GET /api/v1/reconciliation/runs?limit=20` | OPERATOR, AUDITOR, ADMIN | Read 1–50 recent scheduled run reports |
| `POST /api/v1/reconciliation/rebuild` | OPERATOR, ADMIN | Lock accounts and repair mismatched projections |

The read operation reports mismatches without changing data. Rebuild updates only balance
projections and returns the previous and rebuilt values; it never edits ledger history.

## Users and audit events

| Method and path | Access | Description |
|---|---|---|
| `POST /api/v1/users` | ADMIN | Create a user |
| `GET /api/v1/users` | AUDITOR, ADMIN | List users without password hashes |
| `PATCH /api/v1/users/{userId}/role` | ADMIN | Change a non-bootstrap user's role |
| `GET /api/v1/audit-events` | AUDITOR, ADMIN | Read append-only audit events |

User creation accepts `username`, `password` and `role`. Usernames contain 3–80 letters, digits,
periods, underscores or hyphens. Passwords contain 12–200 characters. A CUSTOMER who owns an
account cannot move to another role, and the four configured bootstrap users keep their assigned
roles.

Audit event pagination accepts `limit` from 1 through 100 and an optional numeric `before` cursor.

## Actuator

| Method and path | Access | Description |
|---|---|---|
| `GET /actuator/health` | Public | Application and database health |
| `GET /actuator/info` | Public | Application information |
| `GET /actuator/metrics` | OPERATOR, AUDITOR, ADMIN | Available metric names |
| `GET /actuator/metrics/{metricName}` | OPERATOR, AUDITOR, ADMIN | Measurements and tags for one metric |

## Error responses

Errors use `application/problem+json`. Each response contains the HTTP status, a stable `code`, a
human-readable `detail` and a timestamp. Validation responses also include an `errors` object keyed
by request field.

Common codes include:

| Code | Meaning |
|---|---|
| `VALIDATION_FAILED`, `INVALID_REQUEST` | The body, path or query input is invalid |
| `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED` | Authentication is missing or the role is not allowed |
| `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `USER_NOT_FOUND` | The requested resource is unavailable to the caller |
| `UNSUPPORTED_CURRENCY`, `CURRENCY_MISMATCH` | Currency is unsupported or does not match an account |
| `INSUFFICIENT_FUNDS` | An outgoing operation would make a customer balance negative |
| `ACCOUNT_FROZEN`, `ACCOUNT_CLOSED`, `ACCOUNT_NOT_EMPTY` | Account state prevents the operation |
| `INVALID_IDEMPOTENCY_KEY`, `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_INCOMPLETE` | The idempotency key is invalid or cannot be replayed |
| `ALREADY_REVERSED`, `REVERSAL_OF_REVERSAL` | Reversal rules prevent the operation |
| `OWNER_NOT_FOUND`, `INVALID_ACCOUNT_OWNER` | The selected account owner is unavailable or is not an enabled CUSTOMER |
| `USERNAME_EXISTS`, `USER_OWNS_ACCOUNTS`, `BOOTSTRAP_USER_IMMUTABLE` | A user administration rule prevents the change |
| `INTEGRITY_CONFLICT` | PostgreSQL rejected a write that violated a ledger constraint |

Clients should branch on `code`; `detail` is written for people and may change.
