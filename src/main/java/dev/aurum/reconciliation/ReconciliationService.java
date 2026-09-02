package dev.aurum.reconciliation;

import dev.aurum.audit.AuditAction;
import dev.aurum.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final String MISMATCH_QUERY = """
            SELECT a.id, a.currency, b.balance_minor,
                   COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                     THEN e.amount_minor ELSE -e.amount_minor END), 0)::BIGINT
                       AS ledger_balance
              FROM account a
              JOIN account_balance b ON b.account_id = a.id
              LEFT JOIN ledger_entry e ON e.account_id = a.id
             GROUP BY a.id, a.currency, b.balance_minor
            HAVING b.balance_minor <>
                   COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                     THEN e.amount_minor ELSE -e.amount_minor END), 0)::BIGINT
             ORDER BY a.id
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();

    public ReconciliationService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ReconciliationResult reconcile() {
        List<BalanceMismatch> mismatches = findMismatches();
        return new ReconciliationResult(mismatches.isEmpty(), mismatches);
    }

    @Transactional
    public RebuildResult rebuild() {
        List<UUID> accountIds = jdbc.queryForList(
                "SELECT id FROM account ORDER BY id", UUID.class);

        for (UUID accountId : accountIds) {
            jdbc.queryForObject("""
                    SELECT a.id
                      FROM account a
                      JOIN account_balance b ON b.account_id = a.id
                     WHERE a.id = ?
                     FOR UPDATE OF a, b
                    """, UUID.class, accountId);
        }

        List<BalanceMismatch> mismatches = findMismatches();
        Instant rebuiltAt = Instant.now(clock);
        for (BalanceMismatch mismatch : mismatches) {
            jdbc.update("""
                    UPDATE account_balance
                       SET balance_minor = ?, updated_at = ?
                     WHERE account_id = ?
                    """, mismatch.ledgerBalanceMinor(), Timestamp.from(rebuiltAt), mismatch.accountId());
        }

        List<BalanceRepair> repairs = mismatches.stream()
                .map(mismatch -> new BalanceRepair(
                        mismatch.accountId(), mismatch.currency(),
                        mismatch.projectedBalanceMinor(), mismatch.ledgerBalanceMinor()))
                .toList();
        RebuildResult result = new RebuildResult(accountIds.size(), repairs.size(), rebuiltAt, repairs);
        audit.record(AuditAction.REBUILD_PROJECTIONS, "RECONCILIATION", rebuiltAt,
                Integer.toString(repairs.size()));
        return result;
    }

    private List<BalanceMismatch> findMismatches() {
        return jdbc.query(MISMATCH_QUERY, (resultSet, rowNumber) -> new BalanceMismatch(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("currency"),
                resultSet.getLong("balance_minor"),
                resultSet.getLong("ledger_balance")
        ));
    }

    public record ReconciliationResult(boolean consistent, List<BalanceMismatch> mismatches) {
    }

    public record BalanceMismatch(
            UUID accountId,
            String currency,
            long projectedBalanceMinor,
            long ledgerBalanceMinor
    ) {
    }

    public record RebuildResult(
            int accountsScanned,
            int repairedAccounts,
            Instant rebuiltAt,
            List<BalanceRepair> repairs
    ) {
    }

    public record BalanceRepair(
            UUID accountId,
            String currency,
            long previousBalanceMinor,
            long rebuiltBalanceMinor
    ) {
    }
}
