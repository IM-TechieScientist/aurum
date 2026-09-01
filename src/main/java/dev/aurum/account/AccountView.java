package dev.aurum.account;

import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID id,
        String ownerName,
        String currency,
        AccountType accountType,
        EntryDirection normalSide,
        AccountStatus status,
        long balanceMinor,
        Instant createdAt
) {
}

