package dev.aurum.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationRunService {

    public static final long ADVISORY_LOCK_KEY = 4_285_825_671_901L;

    private final JdbcTemplate jdbc;
    private final ReconciliationService reconciliation;
    private final ReconciliationRunRepository runs;
    private final Clock clock = Clock.systemUTC();

    public ReconciliationRunService(JdbcTemplate jdbc, ReconciliationService reconciliation,
                                    ReconciliationRunRepository runs) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
        this.runs = runs;
    }

    @Transactional
    public RunAttempt execute() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            return RunAttempt.skipped();
        }

        Instant startedAt = Instant.now(clock);
        ReconciliationService.ReconciliationResult result = reconciliation.reconcile();
        int accountsScanned = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        Instant completedAt = Instant.now(clock);
        ReconciliationRunView run = new ReconciliationRunView(
                UUID.randomUUID(),
                result.consistent()
                        ? ReconciliationRunView.Status.CONSISTENT
                        : ReconciliationRunView.Status.MISMATCHED,
                accountsScanned,
                result.mismatches().size(),
                startedAt,
                completedAt,
                result.mismatches());
        runs.insert(run);
        return RunAttempt.executed(run);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRunView> recent(int limit) {
        return runs.recent(limit);
    }

    public record RunAttempt(boolean executed, ReconciliationRunView run) {
        static RunAttempt skipped() {
            return new RunAttempt(false, null);
        }

        static RunAttempt executed(ReconciliationRunView run) {
            return new RunAttempt(true, run);
        }
    }
}
