package dev.aurum.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ReconciliationRunRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ReconciliationRunView run) {
        jdbc.update("""
                INSERT INTO reconciliation_run
                    (id, status, accounts_scanned, mismatch_count, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, run.id(), run.status().name(), run.accountsScanned(), run.mismatchCount(),
                Timestamp.from(run.startedAt()), Timestamp.from(run.completedAt()));
        for (ReconciliationService.BalanceMismatch mismatch : run.mismatches()) {
            jdbc.update("""
                    INSERT INTO reconciliation_run_mismatch
                        (run_id, account_id, currency, projected_balance_minor, ledger_balance_minor)
                    VALUES (?, ?, ?, ?, ?)
                    """, run.id(), mismatch.accountId(), mismatch.currency(),
                    mismatch.projectedBalanceMinor(), mismatch.ledgerBalanceMinor());
        }
    }

    public List<ReconciliationRunView> recent(int limit) {
        List<RunHeader> headers = jdbc.query("""
                SELECT id, status, accounts_scanned, mismatch_count, started_at, completed_at
                  FROM reconciliation_run
                 ORDER BY completed_at DESC, id DESC
                 LIMIT ?
                """, (resultSet, rowNumber) -> {
            UUID runId = resultSet.getObject("id", UUID.class);
            return new RunHeader(
                    runId,
                    ReconciliationRunView.Status.valueOf(resultSet.getString("status")),
                    resultSet.getInt("accounts_scanned"),
                    resultSet.getInt("mismatch_count"),
                    resultSet.getTimestamp("started_at").toInstant(),
                    resultSet.getTimestamp("completed_at").toInstant());
        }, limit);
        return headers.stream()
                .map(header -> new ReconciliationRunView(
                        header.id(), header.status(), header.accountsScanned(), header.mismatchCount(),
                        header.startedAt(), header.completedAt(), mismatches(header.id())))
                .toList();
    }

    private List<ReconciliationService.BalanceMismatch> mismatches(UUID runId) {
        return jdbc.query("""
                SELECT account_id, currency, projected_balance_minor, ledger_balance_minor
                  FROM reconciliation_run_mismatch
                 WHERE run_id = ?
                 ORDER BY account_id
                """, (resultSet, rowNumber) -> new ReconciliationService.BalanceMismatch(
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("currency"),
                resultSet.getLong("projected_balance_minor"),
                resultSet.getLong("ledger_balance_minor")), runId);
    }

    private record RunHeader(
            UUID id,
            ReconciliationRunView.Status status,
            int accountsScanned,
            int mismatchCount,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
