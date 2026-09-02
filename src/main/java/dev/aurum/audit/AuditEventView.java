package dev.aurum.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
        long id,
        UUID actorUserId,
        String actorUsername,
        AuditAction action,
        String targetType,
        String targetId,
        String correlationId,
        Instant occurredAt
) {
}
