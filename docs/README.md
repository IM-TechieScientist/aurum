# Aurum documentation

Aurum deliberately keeps operational and design explanations outside the source packages while
making them directly discoverable from the project README.

- [Architecture](architecture.md) explains module boundaries, double-entry accounting and the
  persistence model.
- [API](api.md) documents endpoints, request conventions, responses and stable error codes.
- [Reliability](reliability.md) covers transactions, locking, idempotency, retries,
  reconciliation and projection rebuilding.
- [Testing](testing.md) describes example-based, HTTP, concurrency, database-constraint and
  property-based verification.
- [Observability](observability.md) lists the custom metrics, tags and Actuator queries.
- [Security](security.md) documents authentication, roles, endpoint permissions and limitations.
- [SQL benchmarking](benchmarking.md) defines the synthetic workload, commands and result format.
- [Scheduled reconciliation](reconciliation.md) explains coordination, durable reports and configuration.

The root [README](../README.md) remains the quickest path for setup and a runnable demonstration.
