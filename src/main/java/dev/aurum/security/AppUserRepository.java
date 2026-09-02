package dev.aurum.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class AppUserRepository {

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUserView> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, enabled, created_at
                  FROM app_user
                 WHERE username = ?
                """, this::map, username).stream().findFirst();
    }

    public Optional<AppUserView> findById(UUID id) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, enabled, created_at
                  FROM app_user
                 WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public void updateBootstrapIdentity(UUID id, String username, String passwordHash,
                                        UserRole role, Instant now) {
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, enabled, created_at)
                VALUES (?, ?, ?, ?, TRUE, ?)
                ON CONFLICT (id) DO UPDATE
                   SET username = EXCLUDED.username,
                       password_hash = EXCLUDED.password_hash,
                       role = EXCLUDED.role,
                       enabled = TRUE
                """, id, username, passwordHash, role.name(), Timestamp.from(now));
    }

    public AppUserView create(UUID id, String username, String passwordHash,
                              UserRole role, Instant now) {
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, enabled, created_at)
                VALUES (?, ?, ?, ?, TRUE, ?)
                """, id, username, passwordHash, role.name(), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public List<AppUserView> findAll() {
        return jdbc.query("""
                SELECT id, username, password_hash, role, enabled, created_at
                  FROM app_user
                 ORDER BY username
                """, this::map);
    }

    public void updateRole(UUID id, UserRole role) {
        jdbc.update("UPDATE app_user SET role = ? WHERE id = ?", role.name(), id);
    }

    public int ownedAccountCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE owner_user_id = ?", Integer.class, id);
    }

    private AppUserView map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AppUserView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getBoolean("enabled"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
