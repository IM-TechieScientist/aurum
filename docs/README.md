# Aurum documentation

These guides describe Aurum's design, API, correctness guarantees, security model, verification
strategy and delivery workflow.

## Start here

1. [Architecture and accounting](architecture.md) explains the system structure, data model and
   double-entry rules.
2. [REST API](api.md) lists endpoints, request rules, authorization and error responses.
3. [Reliability](reliability.md) covers locking, idempotency, retries, reconciliation and failure
   recovery.
4. [Testing](testing.md) shows how each guarantee is verified against PostgreSQL and HTTP.

## Reference guides

- [Authentication and authorization](security.md)
- [Append-only audit events](audit.md)
- [Scheduled reconciliation](reconciliation.md)
- [Metrics and observability](observability.md)
- [SQL benchmarking](benchmarking.md)
- [HTTP load testing](load-testing.md)
- [Operations and delivery](operations.md)

Return to the root [README](../README.md) for setup instructions and a command-line walkthrough.
