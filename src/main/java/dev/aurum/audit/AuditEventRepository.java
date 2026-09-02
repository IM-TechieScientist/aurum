package dev.aurum.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditEventRepository {

    private static final String SELECT_EVENT = """
            SELECT id, actor_user_id, actor_username, action, target_type, target_id,
                   correlation_id, occurred_at
              FROM audit_event
            """;

    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID actorUserId, String actorUsername, AuditAction action,
                       String targetType, String targetId, String correlationId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_event
                    (actor_user_id, actor_username, action, target_type, target_id,
                     correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, actorUserId, actorUsername, action.name(), targetType, targetId,
                correlationId, Timestamp.from(occurredAt));
    }

    public List<AuditEventView> recent(Long before, int limit) {
        if (before == null) {
            return jdbc.query(SELECT_EVENT + " ORDER BY id DESC LIMIT ?", this::map, limit);
        }
        return jdbc.query(SELECT_EVENT + " WHERE id < ? ORDER BY id DESC LIMIT ?",
                this::map, before, limit);
    }

    private AuditEventView map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEventView(
                resultSet.getLong("id"),
                resultSet.getObject("actor_user_id", UUID.class),
                resultSet.getString("actor_username"),
                AuditAction.valueOf(resultSet.getString("action")),
                resultSet.getString("target_type"),
                resultSet.getString("target_id"),
                resultSet.getString("correlation_id"),
                resultSet.getTimestamp("occurred_at").toInstant());
    }
}
