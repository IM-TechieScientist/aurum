package dev.aurum.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionView(
        UUID id,
        TransactionType type,
        String reference,
        UUID reversalOf,
        Instant createdAt,
        List<LedgerEntryView> entries
) {
}

