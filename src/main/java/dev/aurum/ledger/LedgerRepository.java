package dev.aurum.ledger;

import dev.aurum.account.EntryDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTransaction(UUID id, TransactionType type, String reference,
                                  UUID reversalOf, Instant now) {
        jdbc.update("""
                INSERT INTO ledger_transaction
                    (id, transaction_type, reference, reversal_of, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, type.name(), reference, reversalOf, Timestamp.from(now));
    }

    public void insertEntry(UUID id, UUID transactionId, LedgerEntryDraft entry, Instant now) {
        jdbc.update("""
                INSERT INTO ledger_entry
                    (id, transaction_id, account_id, direction, amount_minor, currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, transactionId, entry.accountId(), entry.direction().name(),
                entry.amountMinor(), entry.currency(), Timestamp.from(now));
    }

    public Optional<TransactionView> find(UUID transactionId) {
        List<TransactionHeader> headers = jdbc.query("""
                SELECT id, transaction_type, reference, reversal_of, created_at
                  FROM ledger_transaction WHERE id = ?
                """, this::mapHeader, transactionId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        TransactionHeader header = headers.getFirst();
        return Optional.of(new TransactionView(
                header.id(), header.type(), header.reference(), header.reversalOf(), header.createdAt(),
                findEntries(transactionId)
        ));
    }

    public List<LedgerEntryView> findEntries(UUID transactionId) {
        return jdbc.query("""
                SELECT id, account_id, direction, amount_minor, currency
                  FROM ledger_entry
                 WHERE transaction_id = ?
                 ORDER BY id
                """, (resultSet, rowNumber) -> new LedgerEntryView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                EntryDirection.valueOf(resultSet.getString("direction")),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency")
        ), transactionId);
    }

    public Optional<UUID> findReversal(UUID originalTransactionId) {
        return jdbc.query("SELECT id FROM ledger_transaction WHERE reversal_of = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                originalTransactionId).stream().findFirst();
    }

    public List<TransactionSummary> history(UUID accountId, UUID beforeTransactionId, int limit) {
        if (beforeTransactionId == null) {
            return jdbc.query("""
                    SELECT t.id, t.transaction_type, t.reference, t.reversal_of, t.created_at
                      FROM ledger_entry e
                      JOIN ledger_transaction t ON t.id = e.transaction_id
                     WHERE e.account_id = ?
                     ORDER BY e.created_at DESC, e.transaction_id DESC
                     LIMIT ?
                    """, this::mapSummary, accountId, limit);
        }

        List<TransactionHeader> cursors = jdbc.query("""
                SELECT id, transaction_type, reference, reversal_of, created_at
                  FROM ledger_transaction WHERE id = ?
                """, this::mapHeader, beforeTransactionId);
        if (cursors.isEmpty()) {
            return List.of();
        }
        TransactionHeader cursor = cursors.getFirst();
        return jdbc.query("""
                SELECT t.id, t.transaction_type, t.reference, t.reversal_of, t.created_at
                  FROM ledger_entry e
                  JOIN ledger_transaction t ON t.id = e.transaction_id
                 WHERE e.account_id = ?
                   AND (e.created_at, e.transaction_id) < (?, ?)
                 ORDER BY e.created_at DESC, e.transaction_id DESC
                 LIMIT ?
                """, this::mapSummary, accountId, Timestamp.from(cursor.createdAt()), cursor.id(), limit);
    }

    public boolean isVisibleToOwner(UUID transactionId, String username) {
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ledger_entry e
                      JOIN account a ON a.id = e.account_id
                      JOIN app_user u ON u.id = a.owner_user_id
                     WHERE e.transaction_id = ? AND u.username = ?
                )
                """, Boolean.class, transactionId, username);
        return Boolean.TRUE.equals(visible);
    }

    private TransactionHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TransactionHeader(
                resultSet.getObject("id", UUID.class),
                TransactionType.valueOf(resultSet.getString("transaction_type")),
                resultSet.getString("reference"),
                resultSet.getObject("reversal_of", UUID.class),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private TransactionSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TransactionSummary(
                resultSet.getObject("id", UUID.class),
                TransactionType.valueOf(resultSet.getString("transaction_type")),
                resultSet.getString("reference"),
                resultSet.getObject("reversal_of", UUID.class),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private record TransactionHeader(
            UUID id,
            TransactionType type,
            String reference,
            UUID reversalOf,
            Instant createdAt
    ) {
    }
}
