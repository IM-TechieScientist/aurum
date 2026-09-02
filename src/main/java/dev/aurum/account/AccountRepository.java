package dev.aurum.account;

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
public class AccountRepository {

    private static final String SELECT_ACCOUNT = """
            SELECT a.id, a.owner_name, a.owner_user_id, u.username AS owner_username,
                   a.currency, a.account_type, a.normal_side,
                   a.status, a.created_at, b.balance_minor
              FROM account a
              JOIN account_balance b ON b.account_id = a.id
              LEFT JOIN app_user u ON u.id = a.owner_user_id
            """;

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountView create(String ownerName, UUID ownerUserId, String currency, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account
                    (id, owner_name, owner_user_id, currency, account_type, normal_side, status, created_at)
                VALUES (?, ?, ?, ?, 'CUSTOMER', 'CREDIT', 'ACTIVE', ?)
                """, id, ownerName, ownerUserId, currency, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO account_balance (account_id, balance_minor, updated_at)
                VALUES (?, 0, ?)
                """, id, Timestamp.from(now));
        return find(id).orElseThrow();
    }

    public Optional<AccountView> find(UUID id) {
        return jdbc.query(SELECT_ACCOUNT + " WHERE a.id = ?", this::map, id).stream().findFirst();
    }

    public Optional<AccountView> findSettlement(String currency) {
        return jdbc.query(SELECT_ACCOUNT + " WHERE a.currency = ? AND a.account_type = 'SETTLEMENT'",
                this::map, currency).stream().findFirst();
    }

    public AccountView lock(UUID id) {
        List<AccountView> results = jdbc.query(SELECT_ACCOUNT + " WHERE a.id = ? FOR UPDATE OF a, b",
                this::map, id);
        return results.isEmpty() ? null : results.getFirst();
    }

    public void updateBalance(UUID accountId, long newBalance, Instant now) {
        jdbc.update("""
                UPDATE account_balance SET balance_minor = ?, updated_at = ? WHERE account_id = ?
                """, newBalance, Timestamp.from(now), accountId);
    }

    public boolean updateStatus(UUID accountId, AccountStatus status) {
        return jdbc.update("UPDATE account SET status = ? WHERE id = ? AND account_type = 'CUSTOMER'",
                status.name(), accountId) == 1;
    }

    public boolean isOwnedBy(UUID accountId, String username) {
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account a
                      JOIN app_user u ON u.id = a.owner_user_id
                     WHERE a.id = ? AND u.username = ?
                )
                """, Boolean.class, accountId, username);
        return Boolean.TRUE.equals(owned);
    }

    private AccountView map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccountView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("owner_name"),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getString("owner_username"),
                resultSet.getString("currency"),
                AccountType.valueOf(resultSet.getString("account_type")),
                EntryDirection.valueOf(resultSet.getString("normal_side")),
                AccountStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("balance_minor"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
