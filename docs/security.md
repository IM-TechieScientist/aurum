# Authentication and authorization

## Authentication

Aurum uses stateless HTTP Basic authentication through Spring Security. User records live in
PostgreSQL, and passwords are stored with Spring Security's PBKDF2 encoder. The server does not
create HTTP sessions or cache requests.

Four bootstrap identities provide access to each role during local development:

| Role | Default username | Default password |
|---|---|---|
| CUSTOMER | `customer` | `customer-local` |
| OPERATOR | `operator` | `operator-local` |
| AUDITOR | `auditor` | `auditor-local` |
| ADMIN | `admin` | `admin-local` |

Configure each identity with `AURUM_<ROLE>_USERNAME` and `AURUM_<ROLE>_PASSWORD`. Startup keeps the
fixed bootstrap user IDs and updates their configured usernames, password hashes, enabled state and
roles. The bootstrap roles cannot be changed through the API.

The checked-in credentials are suitable only for local use. HTTP Basic sends credentials with each
request, so any shared deployment must provide TLS and replace all default passwords.

## Permission matrix

| Capability | CUSTOMER | OPERATOR | AUDITOR | ADMIN |
|---|:---:|:---:|:---:|:---:|
| Read accounts and transactions | Owned resources | All | All | All |
| Withdraw and transfer | Owned source | All | No | All |
| Create and fund accounts | No | Yes | No | Yes |
| Freeze, unfreeze or close accounts | No | Yes | No | Yes |
| Reverse transactions | No | Yes | No | Yes |
| Read reconciliation and run reports | No | Yes | Yes | Yes |
| Rebuild balance projections | No | Yes | No | Yes |
| Read application metrics | No | Yes | Yes | Yes |
| List users | No | No | Yes | Yes |
| Create users or change roles | No | No | No | Yes |
| Read audit events | No | No | Yes | Yes |
| Read health and information | Public | Public | Public | Public |

Routes and HTTP methods that do not match this policy are denied.

## Account-level access

Each customer account has an `owner_user_id`. For a CUSTOMER request:

- account details, balances and history require account ownership;
- withdrawals require ownership of the account;
- transfers require ownership of the source account;
- transaction details require at least one entry involving an owned account.

OPERATOR, AUDITOR and ADMIN reads are not restricted by ownership. When a CUSTOMER requests
another user's account or transaction, Aurum returns the same 404 response used for an unknown
resource, avoiding disclosure of the resource's existence.

Only an enabled CUSTOMER can own a newly created account. An ADMIN cannot change an account-owning
CUSTOMER to another role because that would violate the ownership model.

## User administration

ADMIN users can create durable users with CUSTOMER, OPERATOR, AUDITOR or ADMIN roles. New usernames
are normalized to lowercase and must be unique. The API never returns a password hash.

AUDITOR and ADMIN users can list users. ADMIN users can change roles for users other than the four
bootstrap identities. User creation and effective role changes append audit events in the same
transaction as the user update.

## Security error responses

- Missing or invalid credentials return HTTP 401 with `AUTHENTICATION_REQUIRED` and a Basic
  authentication challenge.
- Valid credentials without the required role return HTTP 403 with `ACCESS_DENIED`.
- CUSTOMER access to another user's resource returns HTTP 404.

These responses use the same `application/problem+json` structure as domain errors.

## Deployment boundary

The application terminates HTTP and exposes Actuator on the same port. TLS termination, network
segmentation, rate limiting and secret delivery belong to the environment running the container.
The current API does not provide password rotation, account lockout or ownership transfer.
