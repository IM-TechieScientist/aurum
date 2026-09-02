package dev.aurum.audit;

import dev.aurum.security.AppUserRepository;
import dev.aurum.security.AppUserView;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AuditService {

    private final AuditEventRepository events;
    private final AppUserRepository users;
    private final Clock clock = Clock.systemUTC();

    public AuditService(AuditEventRepository events, AppUserRepository users) {
        this.events = events;
        this.users = users;
    }

    public void record(AuditAction action, String targetType, Object targetId, String correlationId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null || !authentication.isAuthenticated()
                ? "SYSTEM" : authentication.getName();
        AppUserView actor = users.findByUsername(username).orElse(null);
        events.insert(actor == null ? null : actor.id(), username, action,
                targetType, targetId.toString(), correlationId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> recent(Long before, int limit) {
        return events.recent(before, limit);
    }
}
