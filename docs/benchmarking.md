# SQL benchmarking

Aurum includes an opt-in PostgreSQL benchmark named `SqlBenchmark`. It does not run in ordinary
`mvn verify` or GitHub Actions builds, so it adds no routine CI time or retained database storage.

## Workload

The default fixture contains:

- 1,000 customer accounts;
- 100,000 balanced transfer transactions;
- 200,000 immutable ledger entries;
- one hot account participating in every transaction.

It measures projected-balance lookup, newest-20 hot-account history and full-ledger reconciliation.
Every run also captures PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plans and checks that
balance and history queries use their intended indexes.

The report also executes the pre-optimization history query a small number of times. Keeping this
legacy comparison in the harness makes the improvement reproducible without retaining a second
database snapshot or generated dataset.

Fixture rows are generated with set-based SQL inside an ephemeral Testcontainers database. Trigger
execution is disabled only for the fixture-loading transaction to keep setup time separate from the
read measurements. The generated postings remain balanced, single-currency and reconciliation-safe.

## Run it

```bash
mvn -Dtest=SqlBenchmark test
```

Override scale when needed:

```bash
mvn -Dtest=SqlBenchmark \
  -Daurum.benchmark.accounts=2000 \
  -Daurum.benchmark.transactions=500000 test
```

The transaction count must be a positive even number. Results and full JSON plans are written to
`target/benchmarks/sql-benchmark.md`; `mvn clean` removes them. PostgreSQL containers and their
writable layers are also removed automatically after the run.

## Reading results

Client-observed percentiles include local JDBC and Java result mapping. The plan's execution time
isolates PostgreSQL more closely. Neither should be presented as a production SLO: hardware,
container limits, dataset distribution, concurrency and cache temperature all affect results.

Use the benchmark to compare a controlled change on the same machine. Do not add a hard latency
gate to CI; assert correctness and index selection, then record performance as evidence.

## Reference optimization run

The 2026-09-02 development-machine run used PostgreSQL 16.15, Java 21.0.12 and the default
100,000-transaction dataset. These figures are evidence for the query change, not an SLO:

| Query | p50 | p95 | p99 | PostgreSQL execution |
|---|---:|---:|---:|---:|
| Projected balance | 0.146 ms | 0.206 ms | 0.237 ms | 0.029 ms |
| Legacy hot-account history | 144.344 ms | 148.580 ms | 150.288 ms | 73.179 ms |
| Indexed hot-account history | 1.326 ms | 1.472 ms | 1.561 ms | 1.017 ms |
| Full reconciliation | 62.327 ms | 62.860 ms | 62.860 ms | 91.702 ms |

The production history query's median client latency improved by approximately **109x**. Its
PostgreSQL plan changed from scanning/joining and externally sorting the hot-account result set to
an ordered `ledger_entry_account_history` index-only scan followed by primary-key transaction
lookups. Server execution improved by approximately **72x** in this run.

The client and plan reconciliation timings are separate executions and can differ with parallel
planning and cache state; compare each metric only with the same metric from another run.
