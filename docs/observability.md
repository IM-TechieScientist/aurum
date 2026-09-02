# Metrics and observability

Spring Boot Actuator exposes application health, information and Micrometer metrics. Aurum uses a
fixed set of tag values so the metrics remain safe to aggregate; account IDs, transaction IDs and
idempotency keys never become metric tags.

## Endpoints

Health and application information are public. OPERATOR, AUDITOR and ADMIN users can access metric
endpoints.

```bash
curl -fsS localhost:8080/actuator/health
curl -fsS -u auditor:auditor-local localhost:8080/actuator/metrics
```

## Application metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `aurum.transfer.operations` | Counter | `outcome=success\|business_failure\|system_failure` | Transfer calls by final result |
| `aurum.transfer.duration` | Timer | same outcome values | Complete transfer latency, including database retries |
| `aurum.transfer.retries` | Counter | `reason=deadlock\|serialization_failure` | New transfer attempts after retryable PostgreSQL errors |
| `aurum.idempotency.requests` | Counter | `outcome=claimed\|replayed\|conflict\|incomplete` | Idempotency claim decisions across money routes |
| `aurum.reconciliation.runs` | Counter | `outcome=consistent\|mismatched\|skipped\|error` | Scheduled reconciliation attempts |
| `aurum.reconciliation.duration` | Timer | same outcome values | Scheduled reconciliation duration |
| `aurum.reconciliation.last.mismatches` | Gauge | none | Mismatches found by the latest completed run |

An idempotent replay counts as a successful transfer call and an idempotency `replayed` decision.
Domain-rule rejections count as `business_failure`; unexpected runtime or database failures count
as `system_failure`.

## Query examples

```bash
curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.transfer.operations

curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.idempotency.requests

curl -fsS -u auditor:auditor-local \
  localhost:8080/actuator/metrics/aurum.reconciliation.runs
```

Actuator runs on the application port. A deployment can place that port behind network controls or
route the management paths separately. The project currently exposes Micrometer's built-in meter
registry through Actuator; it does not include a Prometheus registry or Grafana dashboard.
