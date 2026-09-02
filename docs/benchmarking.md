# SQL benchmarking

`SqlBenchmark` measures the PostgreSQL queries that support balances, account history and
reconciliation. It runs against a fresh Testcontainers database and writes a Markdown report with
client latency percentiles and full JSON query plans.

## Dataset

The default fixture contains:

- 1,000 customer accounts;
- 100,000 balanced transfer transactions;
- 200,000 immutable ledger entries;
- one account involved in every transaction.

The benchmark measures:

1. projected balance lookup by account ID;
2. the previous account-history query;
3. the current newest-20 account-history query;
4. full-ledger reconciliation.

Fixture rows are generated with set-based SQL. Trigger execution is disabled only while loading
the fixture so setup cost does not dominate the read measurements. The harness verifies row counts,
balanced projections and a clean reconciliation result before collecting timings.

For the balance and current history queries, the test also checks that PostgreSQL selected the
expected indexes. Every measured query receives `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` output.

## Running the benchmark

```bash
mvn -Dtest=SqlBenchmark test
```

Change the fixture size with JVM properties:

```bash
mvn -Dtest=SqlBenchmark \
  -Daurum.benchmark.accounts=2000 \
  -Daurum.benchmark.transactions=500000 test
```

The account count must be at least two. The transaction count must be positive and even. The report
is written to `target/benchmarks/sql-benchmark.md`; `mvn clean` removes the report and generated
build files.

The benchmark is separate from `mvn verify` so routine CI remains focused on correctness. Compare
runs using the same machine, JVM, PostgreSQL image, fixture size and cache conditions.

## Reference result

This result was recorded on 2026-09-02 with PostgreSQL 16.15, Java 21.0.12 and the default dataset:

| Query | p50 | p95 | p99 | PostgreSQL execution |
|---|---:|---:|---:|---:|
| Projected balance | 0.133 ms | 0.178 ms | 0.206 ms | 0.030 ms |
| Previous hot-account history | 145.875 ms | 149.247 ms | 150.812 ms | 74.557 ms |
| Indexed hot-account history | 1.401 ms | 1.796 ms | 1.983 ms | 0.917 ms |
| Full reconciliation | 62.953 ms | 63.807 ms | 63.807 ms | 94.230 ms |

The current history query reduced median client-observed latency by approximately **104x** and
PostgreSQL execution time by approximately **81x** in this run. Its plan reads the newest entries
through `ledger_entry_account_history`, then resolves transaction details by primary key. The
previous query scanned and joined the full hot-account history before sorting it.

Client timings include JDBC and Java mapping. PostgreSQL execution timings come from separate
`EXPLAIN ANALYZE` executions, so each column should be compared with the same measurement in another
run rather than across columns.
