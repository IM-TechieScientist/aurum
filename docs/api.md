# REST API contract

All application endpoints are under `/api/v1`. JSON amounts are integer minor units. Currency
codes accept three letters and are normalized to uppercase. Money-changing requests require an
`Idempotency-Key` header containing 1–128 characters.

Except for health and info, endpoints require HTTP Basic authentication. The complete role matrix
and credential configuration are in the [security guide](security.md).

## Accounts

| Method and path | Purpose |
|---|---|
| `POST /api/v1/accounts` | Create an INR or USD customer account |
| `GET /api/v1/accounts/{accountId}` | Read account details and projected balance |
| `GET /api/v1/accounts/{accountId}/balance` | Read the compact balance view |
| `PATCH /api/v1/accounts/{accountId}/freeze` | Prevent outgoing transfers and withdrawals |
| `PATCH /api/v1/accounts/{accountId}/unfreeze` | Restore outgoing operations |

Frozen accounts may still receive funding and transfers. This is an explicit Aurum Core policy.

## Money operations

| Method and path | Request fields |
|---|---|
| `POST /api/v1/accounts/{accountId}/fund` | `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/accounts/{accountId}/withdraw` | `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/transfers` | `sourceAccountId`, `destinationAccountId`, `amountMinor`, `currency`, optional `reference` |
| `POST /api/v1/transactions/{transactionId}/reversal` | `reason` |

A successful mutation returns HTTP 201 and the complete transaction with its ledger entries.
An exact idempotent replay returns the original transaction and does not post again.

## Queries and operations

| Method and path | Purpose |
|---|---|
| `GET /api/v1/transactions/{transactionId}` | Read a complete transaction |
| `GET /api/v1/accounts/{accountId}/transactions?limit=20&before={transactionId}` | Cursor-paginated history |
| `GET /api/v1/reconciliation` | Report projection mismatches without changing data |
| `GET /api/v1/reconciliation/runs?limit=20` | Read 1–50 recent scheduled-run reports |
| `POST /api/v1/reconciliation/rebuild` | Lock accounts and repair mismatched projections |

The rebuild endpoint requires OPERATOR or ADMIN. It remains an internal operation and should not
be placed on a public network.

## Problem Details

Errors use `application/problem+json` and include a stable `code`, HTTP status, message and
timestamp. Validation failures also contain an `errors` object keyed by field.

Common codes include:

- `VALIDATION_FAILED`
- `ACCOUNT_NOT_FOUND`
- `ACCOUNT_FROZEN`
- `CURRENCY_MISMATCH`
- `INSUFFICIENT_FUNDS`
- `IDEMPOTENCY_CONFLICT`
- `ALREADY_REVERSED`

Clients should branch on `code`, not the human-readable detail string.
