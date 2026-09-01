package dev.aurum.ledger;

import dev.aurum.account.EntryDirection;

import java.util.UUID;

public record LedgerEntryView(
        UUID id,
        UUID accountId,
        EntryDirection direction,
        long amountMinor,
        String currency
) {
}

