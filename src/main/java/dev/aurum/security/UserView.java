package dev.aurum.security;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id,
        String username,
        UserRole role,
        boolean enabled,
        Instant createdAt
) {
    static UserView from(AppUserView user) {
        return new UserView(user.id(), user.username(), user.role(), user.enabled(), user.createdAt());
    }
}
