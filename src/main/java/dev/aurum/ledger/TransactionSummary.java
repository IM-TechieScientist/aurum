package dev.aurum.ledger;

import java.time.Instant;
import java.util.UUID;

public record TransactionSummary(
        UUID id,
        TransactionType type,
        String reference,
        UUID reversalOf,
        Instant createdAt
) {
}

