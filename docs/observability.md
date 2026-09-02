# Metrics and observability

Spring Boot Actuator exposes health, info and metrics. Metric names and tag values are deliberately
bounded; account IDs, transaction IDs and idempotency keys are never used as tags.

## Custom metrics

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `aurum.transfer.operations` | Counter | `outcome=success\|business_failure\|system_failure` | Final result of each transfer call |
| `aurum.transfer.duration` | Timer | same `outcome` values | End-to-end transfer latency, including retries |
| `aurum.transfer.retries` | Counter | `reason=deadlock\|serialization_failure` | Fresh transaction attempts after retryable PostgreSQL aborts |
| `aurum.idempotency.requests` | Counter | `outcome=claimed\|replayed\|conflict\|incomplete` | Database claim decision for all idempotent money routes |
| `aurum.reconciliation.runs` | Counter | `outcome=consistent\|mismatched\|skipped\|error` | Scheduled reconciliation attempts |
| `aurum.reconciliation.duration` | Timer | same `outcome` values | End-to-end scheduled-run duration |
| `aurum.reconciliation.last.mismatches` | Gauge | none | Mismatches in the last completed run |

A successful idempotent replay counts as a successful transfer call and an idempotency `replayed`
decision. A request rejected by a domain rule counts as `business_failure`. Unexpected runtime or
database failures count as `system_failure`.

## Actuator queries

List available metric names:

```bash
curl -fsS -u auditor:auditor-local localhost:8080/actuator/metrics
```

Inspect transfer outcomes:

```bash
curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.transfer.operations
```

Inspect idempotency decisions:

```bash
curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.idempotency.requests
```

Inspect reconciliation outcomes:

```bash
curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.reconciliation.runs
```

The current project exposes Actuator on the application port for local demonstration. A deployed
system should place management endpoints on a protected network/port and add a Prometheus registry
only when an actual scraper is part of the environment.
