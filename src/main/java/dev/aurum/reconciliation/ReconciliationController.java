package dev.aurum.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final JdbcTemplate jdbc;

    public ReconciliationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    ReconciliationResult reconcile() {
        List<BalanceMismatch> mismatches = jdbc.query("""
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
                """, (resultSet, rowNumber) -> new BalanceMismatch(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("currency"),
                resultSet.getLong("balance_minor"),
                resultSet.getLong("ledger_balance")
        ));
        return new ReconciliationResult(mismatches.isEmpty(), mismatches);
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
}
