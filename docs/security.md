# Authentication and RBAC

## Authentication model

Aurum uses stateless HTTP Basic authentication through Spring Security. The server creates no HTTP
session, disables request caching and returns `application/problem+json` for authentication and
authorization failures. Health and info are public; all ledger and management data require a role.

The four demonstration identities are held in memory and their passwords are encoded with PBKDF2
when the application starts. Configure them through environment variables:

| Role | Default username | Password variable |
|---|---|---|
| CUSTOMER | `customer` | `AURUM_CUSTOMER_PASSWORD` |
| OPERATOR | `operator` | `AURUM_OPERATOR_PASSWORD` |
| AUDITOR | `auditor` | `AURUM_AUDITOR_PASSWORD` |
| ADMIN | `admin` | `AURUM_ADMIN_PASSWORD` |

Each username has a matching `AURUM_<ROLE>_USERNAME` variable. Local fallback passwords follow
`<username>-local`; these defaults are deliberately visible and must not be used on a shared host.

## Permission matrix

| Capability | CUSTOMER | OPERATOR | AUDITOR | ADMIN |
|---|:---:|:---:|:---:|:---:|
| Account and transaction reads | yes | yes | yes | yes |
| Withdraw and transfer | yes | yes | no | yes |
| Create and fund accounts | no | yes | no | yes |
| Freeze/unfreeze accounts | no | yes | no | yes |
| Reverse transactions | no | yes | no | yes |
| Read live reconciliation and run history | no | yes | yes | yes |
| Rebuild projections | no | yes | no | yes |
| Read Actuator metrics | no | yes | yes | yes |
| Read Actuator health/info | public | public | public | public |

Unknown routes and unmatched API methods are denied by default.

## Error contract

- Missing or invalid credentials return HTTP 401, `AUTHENTICATION_REQUIRED`, and a Basic challenge.
- Valid credentials without the required role return HTTP 403 and `ACCESS_DENIED`.

Both responses include the same stable Problem Details fields as application errors.

## Deliberate boundary

This milestone implements endpoint-level RBAC. Aurum does not yet associate an authenticated
CUSTOMER identity with particular account IDs, so it is not per-account ownership authorization.
For external customer access, add durable users, account ownership and resource-level checks.

HTTP Basic must be used behind TLS outside local development. Durable identity storage, password
rotation, account lockout and an append-only operator audit log are also production follow-ups.
