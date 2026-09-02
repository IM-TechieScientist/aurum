package dev.aurum.security;

import java.time.Instant;
import java.util.UUID;

public record AppUserView(
        UUID id,
        String username,
        String passwordHash,
        UserRole role,
        boolean enabled,
        Instant createdAt
) {
}
