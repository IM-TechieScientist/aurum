package dev.aurum.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aurum.ledger.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.org.springframework.jdbc.core=OFF"
        })
@Testcontainers
class SqlBenchmark {

    private static final int ACCOUNT_COUNT = Integer.getInteger("aurum.benchmark.accounts", 1_000);
    private static final int TRANSACTION_COUNT = Integer.getInteger(
            "aurum.benchmark.transactions", 100_000);
    private static final int BALANCE_ITERATIONS = 1_000;
    private static final int LEGACY_HISTORY_ITERATIONS = 20;
    private static final int HISTORY_ITERATIONS = 200;
    private static final int RECONCILIATION_ITERATIONS = 5;

    private static final String HISTORY_QUERY = """
            SELECT t.id, t.transaction_type, t.reference, t.reversal_of, t.created_at
              FROM ledger_entry e
              JOIN ledger_transaction t ON t.id = e.transaction_id
             WHERE e.account_id = ?
             ORDER BY e.created_at DESC, e.transaction_id DESC
             LIMIT 20
            """;

    private static final String LEGACY_HISTORY_QUERY = """
            SELECT DISTINCT t.id, t.transaction_type, t.reference, t.reversal_of, t.created_at
              FROM ledger_transaction t
              JOIN ledger_entry e ON e.transaction_id = t.id
             WHERE e.account_id = ?
             ORDER BY t.created_at DESC, t.id DESC
             LIMIT 20
            """;

    private static final String RECONCILIATION_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT a.id
                  FROM account a
                  JOIN account_balance b ON b.account_id = a.id
                  LEFT JOIN ledger_entry e ON e.account_id = a.id
                 GROUP BY a.id, b.balance_minor
                HAVING b.balance_minor <>
                       COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                         THEN e.amount_minor ELSE -e.amount_minor END), 0)::BIGINT
            ) mismatches
            """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aurum_benchmark")
            .withUsername("aurum")
            .withPassword("aurum");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void benchmarkIndexedLedgerQueries() throws Exception {
        requireValidScale();
        Instant startedAt = Instant.now();
        seedSyntheticLedger();
        Duration seedDuration = Duration.between(startedAt, Instant.now());

        UUID hotAccountId = jdbc.queryForObject(
                "SELECT id FROM account WHERE owner_name = 'Benchmark account 1'", UUID.class);
        assertSeedIntegrity(hotAccountId);

        warmUp(hotAccountId);
        Latency balance = measure(BALANCE_ITERATIONS, () -> jdbc.queryForObject(
                "SELECT balance_minor FROM account_balance WHERE account_id = ?",
                Long.class, hotAccountId));
        Latency legacyHistory = measure(LEGACY_HISTORY_ITERATIONS,
                () -> historyRows(LEGACY_HISTORY_QUERY, hotAccountId));
        Latency history = measure(HISTORY_ITERATIONS,
                () -> ledger.history(hotAccountId, null, 20));
        Latency reconciliation = measure(RECONCILIATION_ITERATIONS,
                () -> jdbc.queryForObject(RECONCILIATION_QUERY, Long.class));

        QueryPlan balancePlan = explain("""
                SELECT balance_minor FROM account_balance WHERE account_id = ?
                """, hotAccountId);
        QueryPlan legacyHistoryPlan = explain(LEGACY_HISTORY_QUERY, hotAccountId);
        QueryPlan historyPlan = explain(HISTORY_QUERY, hotAccountId);
        QueryPlan reconciliationPlan = explain(RECONCILIATION_QUERY);

        assertThat(balancePlan.indexNames()).contains("account_balance_pkey");
        assertThat(historyPlan.indexNames()).contains("ledger_entry_account_history");

        String report = report(seedDuration, balance, legacyHistory, history, reconciliation,
                balancePlan, legacyHistoryPlan, historyPlan, reconciliationPlan);
        Path output = Path.of("target", "benchmarks", "sql-benchmark.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report);
        System.out.println(report);
    }

    private void requireValidScale() {
        assertThat(ACCOUNT_COUNT).as("benchmark account count").isGreaterThanOrEqualTo(2);
        assertThat(TRANSACTION_COUNT).as("benchmark transaction count").isPositive().isEven();
    }

    private void seedSyntheticLedger() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO account
                        (id, owner_name, owner_user_id, currency, account_type, normal_side,
                         status, created_at)
                    SELECT md5('benchmark-account-' || sequence)::UUID,
                           'Benchmark account ' || sequence,
                           '00000000-0000-0000-0000-000000000101'::UUID,
                           'INR', 'CUSTOMER', 'CREDIT', 'ACTIVE',
                           TIMESTAMPTZ '2026-01-01 00:00:00+00'
                      FROM generate_series(1, ?) sequence
                    """, ACCOUNT_COUNT);
            jdbc.update("""
                    INSERT INTO account_balance (account_id, balance_minor, updated_at)
                    SELECT id, 0, TIMESTAMPTZ '2026-01-01 00:00:00+00'
                      FROM account
                     WHERE owner_name LIKE 'Benchmark account %'
                    """);
            jdbc.update("""
                    INSERT INTO ledger_transaction
                        (id, transaction_type, reference, reversal_of, created_at)
                    SELECT md5('benchmark-transaction-' || sequence)::UUID,
                           'TRANSFER', 'sql benchmark', NULL,
                           TIMESTAMPTZ '2026-01-01 00:00:00+00'
                               + sequence * INTERVAL '1 millisecond'
                      FROM generate_series(1, ?) sequence
                    """, TRANSACTION_COUNT);
            jdbc.update("""
                    INSERT INTO ledger_entry
                        (id, transaction_id, account_id, direction, amount_minor, currency, created_at)
                    SELECT md5('benchmark-debit-' || sequence)::UUID,
                           md5('benchmark-transaction-' || sequence)::UUID,
                           CASE WHEN sequence % 2 = 1
                                THEN md5('benchmark-account-1')::UUID
                                ELSE md5('benchmark-account-' ||
                                     (2 + (((sequence - 1) / 2) % (? - 1))))::UUID END,
                           'DEBIT', 1, 'INR',
                           TIMESTAMPTZ '2026-01-01 00:00:00+00'
                               + sequence * INTERVAL '1 millisecond'
                      FROM generate_series(1, ?) sequence
                    """, ACCOUNT_COUNT, TRANSACTION_COUNT);
            jdbc.update("""
                    INSERT INTO ledger_entry
                        (id, transaction_id, account_id, direction, amount_minor, currency, created_at)
                    SELECT md5('benchmark-credit-' || sequence)::UUID,
                           md5('benchmark-transaction-' || sequence)::UUID,
                           CASE WHEN sequence % 2 = 0
                                THEN md5('benchmark-account-1')::UUID
                                ELSE md5('benchmark-account-' ||
                                     (2 + (((sequence - 1) / 2) % (? - 1))))::UUID END,
                           'CREDIT', 1, 'INR',
                           TIMESTAMPTZ '2026-01-01 00:00:00+00'
                               + sequence * INTERVAL '1 millisecond'
                      FROM generate_series(1, ?) sequence
                    """, ACCOUNT_COUNT, TRANSACTION_COUNT);
        });
        jdbc.execute("ANALYZE account");
        jdbc.execute("ANALYZE account_balance");
        jdbc.execute("ANALYZE ledger_transaction");
        jdbc.execute("ANALYZE ledger_entry");
    }

    private void assertSeedIntegrity(UUID hotAccountId) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class))
                .isEqualTo(TRANSACTION_COUNT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Long.class))
                .isEqualTo(TRANSACTION_COUNT * 2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", Long.class, hotAccountId))
                .isEqualTo(TRANSACTION_COUNT);
        assertThat(jdbc.queryForObject(RECONCILIATION_QUERY, Long.class)).isZero();
        assertThat(ledger.history(hotAccountId, null, 20)).hasSize(20);
    }

    private void warmUp(UUID hotAccountId) {
        measure(50, () -> jdbc.queryForObject(
                "SELECT balance_minor FROM account_balance WHERE account_id = ?",
                Long.class, hotAccountId));
        measure(10, () -> ledger.history(hotAccountId, null, 20));
        measure(1, () -> jdbc.queryForObject(RECONCILIATION_QUERY, Long.class));
    }

    private List<UUID> historyRows(String sql, UUID accountId) {
        return jdbc.query(sql,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), accountId);
    }

    private Latency measure(int iterations, Supplier<?> query) {
        List<Long> samples = new ArrayList<>(iterations);
        for (int iteration = 0; iteration < iterations; iteration++) {
            long started = System.nanoTime();
            Object result = query.get();
            long elapsed = System.nanoTime() - started;
            assertThat(result).isNotNull();
            samples.add(elapsed);
        }
        Collections.sort(samples);
        return new Latency(percentile(samples, 50), percentile(samples, 95),
                percentile(samples, 99), samples.stream().mapToLong(Long::longValue).average().orElseThrow());
    }

    private double percentile(List<Long> samples, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * samples.size()) - 1;
        return nanosToMillis(samples.get(Math.max(index, 0)));
    }

    private double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

    private QueryPlan explain(String sql, Object... arguments) throws IOException {
        String json = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql,
                (resultSet, rowNumber) -> resultSet.getString(1), arguments);
        JsonNode statement = objectMapper.readTree(json).get(0);
        JsonNode root = statement.get("Plan");
        List<String> indexes = new ArrayList<>();
        collectIndexes(root, indexes);
        return new QueryPlan(statement.get("Execution Time").asDouble(),
                root.path("Shared Hit Blocks").asLong(), List.copyOf(indexes), json);
    }

    private void collectIndexes(JsonNode node, List<String> indexes) {
        if (node.has("Index Name")) {
            indexes.add(node.get("Index Name").asText());
        }
        node.path("Plans").forEach(child -> collectIndexes(child, indexes));
    }

    private String report(Duration seedDuration, Latency balance, Latency legacyHistory,
                          Latency history, Latency reconciliation, QueryPlan balancePlan,
                          QueryPlan legacyHistoryPlan, QueryPlan historyPlan,
                          QueryPlan reconciliationPlan) throws Exception {
        long databaseBytes = jdbc.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);
        return """
                # Aurum SQL benchmark result

                Generated at `%s` using PostgreSQL `%s` on Java `%s`.

                ## Dataset

                - Accounts: %,d customer accounts plus settlement accounts
                - Transactions: %,d balanced transfers
                - Ledger entries: %,d
                - Hot-account entries: %,d
                - Seed time: %.3f s
                - Ephemeral database size: %.2f MiB

                Synthetic rows are structurally valid and balanced. Fixture loading temporarily disables
                triggers only inside the seed transaction; benchmarked reads run with normal database settings.

                ## Client-observed latency

                | Query | Iterations | p50 | p95 | p99 | Mean |
                |---|---:|---:|---:|---:|---:|
                | Projected balance by primary key | %,d | %s | %s | %s | %s |
                | Legacy hot-account history | %,d | %s | %s | %s | %s |
                | Hot-account history, newest 20 | %,d | %s | %s | %s | %s |
                | Full-ledger reconciliation | %,d | %s | %s | %s | %s |

                Times include local JDBC and result-mapping overhead. They are a development-machine baseline,
                not a production service-level objective.

                ## PostgreSQL plans

                | Query | Server execution | Shared-hit blocks | Indexes used |
                |---|---:|---:|---|
                | Projected balance | %.3f ms | %,d | `%s` |
                | Legacy hot-account history | %.3f ms | %,d | `%s` |
                | Hot-account history | %.3f ms | %,d | `%s` |
                | Reconciliation | %.3f ms | %,d | `%s` |

                <details><summary>Balance plan (JSON)</summary>

                ```json
                %s
                ```
                </details>

                <details><summary>Legacy history plan (JSON)</summary>

                ```json
                %s
                ```
                </details>

                <details><summary>History plan (JSON)</summary>

                ```json
                %s
                ```
                </details>

                <details><summary>Reconciliation plan (JSON)</summary>

                ```json
                %s
                ```
                </details>
                """.formatted(
                Instant.now(), jdbc.queryForObject("SHOW server_version", String.class),
                System.getProperty("java.version"), ACCOUNT_COUNT, TRANSACTION_COUNT,
                TRANSACTION_COUNT * 2L, TRANSACTION_COUNT, seedDuration.toMillis() / 1_000.0,
                databaseBytes / 1024.0 / 1024.0,
                BALANCE_ITERATIONS, balance.p50Text(), balance.p95Text(), balance.p99Text(), balance.meanText(),
                LEGACY_HISTORY_ITERATIONS, legacyHistory.p50Text(), legacyHistory.p95Text(),
                legacyHistory.p99Text(), legacyHistory.meanText(),
                HISTORY_ITERATIONS, history.p50Text(), history.p95Text(), history.p99Text(), history.meanText(),
                RECONCILIATION_ITERATIONS, reconciliation.p50Text(), reconciliation.p95Text(),
                reconciliation.p99Text(), reconciliation.meanText(),
                balancePlan.executionMillis(), balancePlan.sharedHitBlocks(),
                String.join(", ", balancePlan.indexNames()),
                legacyHistoryPlan.executionMillis(), legacyHistoryPlan.sharedHitBlocks(),
                String.join(", ", legacyHistoryPlan.indexNames()),
                historyPlan.executionMillis(), historyPlan.sharedHitBlocks(),
                String.join(", ", historyPlan.indexNames()),
                reconciliationPlan.executionMillis(), reconciliationPlan.sharedHitBlocks(),
                String.join(", ", reconciliationPlan.indexNames()),
                pretty(balancePlan.json()), pretty(legacyHistoryPlan.json()),
                pretty(historyPlan.json()), pretty(reconciliationPlan.json()));
    }

    private String pretty(String json) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(json));
    }

    private record Latency(double p50, double p95, double p99, double meanNanos) {
        String p50Text() {
            return millis(p50);
        }

        String p95Text() {
            return millis(p95);
        }

        String p99Text() {
            return millis(p99);
        }

        String meanText() {
            return millis(meanNanos / 1_000_000.0);
        }

        private String millis(double value) {
            return String.format(Locale.ROOT, "%.3f ms", value);
        }
    }

    private record QueryPlan(
            double executionMillis,
            long sharedHitBlocks,
            List<String> indexNames,
            String json
    ) {
    }
}
