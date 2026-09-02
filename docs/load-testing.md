# HTTP load testing

`HttpLoadBenchmark` sends concurrent requests to a real embedded HTTP server backed by a fresh
PostgreSQL Testcontainers database. It uses Java's built-in HTTP client and exercises the same
authentication, controller, service and SQL path used by an API client.

## Workloads

| Workload | Request pattern | Purpose |
|---|---|---|
| Distributed | Transfers rotate across independent account pairs | Measures concurrent traffic spread across different row locks |
| Hot account | Every transfer debits one source account | Measures contention on the same account and balance rows |

Each request includes HTTP Basic authentication, JSON serialization, validation, a unique
idempotency key, ledger posting, balance updates, audit insertion and PostgreSQL commit. The run
fails if any response is not HTTP 201 or if final reconciliation finds a mismatch.

## Running the benchmark

```bash
mvn -Dtest=HttpLoadBenchmark test
```

The default runs 500 transfers per workload with concurrency 8 and eight distributed account pairs.
Change the scale with JVM properties:

```bash
mvn -Dtest=HttpLoadBenchmark \
  -Daurum.load.operations=5000 \
  -Daurum.load.concurrency=32 \
  -Daurum.load.account-pairs=32 test
```

The report is written to `target/benchmarks/http-load.md` and includes throughput, p50, p95, p99
and error count. `mvn clean` removes the report and all generated build output.

The benchmark runs separately from `mvn verify`. For meaningful comparisons, keep the machine,
JVM, PostgreSQL image, operation count and concurrency unchanged.

## Reference result

This result was recorded on 2026-09-02 with Java 21.0.12, PostgreSQL 16, 500 transfers per workload
and concurrency 8:

| Workload | Throughput | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|
| Distributed | 64.9 tx/s | 115.818 ms | 139.803 ms | 499.168 ms | 0 |
| Hot account | 74.5 tx/s | 106.905 ms | 123.120 ms | 132.526 ms | 0 |

Both workloads completed all 500 transfers and finished with a consistent ledger. These figures
describe one development-machine run and provide a reproducible baseline for later changes.
