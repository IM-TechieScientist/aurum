package dev.aurum.account;

import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID id,
        String ownerName,
        UUID ownerUserId,
        String ownerUsername,
        String currency,
        AccountType accountType,
        EntryDirection normalSide,
        AccountStatus status,
        long balanceMinor,
        Instant createdAt
) {
}
