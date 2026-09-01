package dev.aurum.ledger;

import dev.aurum.account.EntryDirection;

import java.util.UUID;

public record LedgerEntryDraft(
        UUID accountId,
        EntryDirection direction,
        long amountMinor,
        String currency
) {
}

