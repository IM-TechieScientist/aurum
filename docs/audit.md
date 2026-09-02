# Append-only audit events

## Recorded operations

Aurum records successful operations that affect access, account state, money or balance repair:

- user creation and role changes;
- account creation, freeze, unfreeze and closure;
- funding, withdrawal, transfer and reversal;
- balance projection rebuilds.

Each event contains a numeric sequence ID, actor user ID, username snapshot, action, target type,
target ID, optional correlation value and UTC timestamp. Money operations use the idempotency key
as their correlation value. Role changes store the previous and new roles. Calls made directly by
application services without an authenticated principal use `SYSTEM` and a null actor user ID.

## Transaction guarantees

The audit insert runs in the same PostgreSQL transaction as the operation it describes. A rejected
request, injected pre-commit failure or database rollback leaves no success event. Replaying a
completed idempotent request returns the original transaction without adding a second event.

PostgreSQL triggers reject `UPDATE` and `DELETE` statements on `audit_event`, and the HTTP API has no
audit mutation route.

## Querying events

AUDITOR and ADMIN users can read events from newest to oldest:

```bash
curl -fsS -u auditor:auditor-local \
  'localhost:8080/api/v1/audit-events?limit=20'
```

Use the final event ID from one response as the next cursor:

```text
GET /api/v1/audit-events?limit=20&before=1234
```

`limit` accepts values from 1 through 100. The `before` cursor is exclusive, which provides stable
keyset pagination while new events are appended.

Audit rows have no automatic retention job. A deployment can export or retain them according to
its operational and compliance requirements.
