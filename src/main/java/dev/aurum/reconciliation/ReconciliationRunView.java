package dev.aurum.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationRunView(
        UUID id,
        Status status,
        int accountsScanned,
        int mismatchCount,
        Instant startedAt,
        Instant completedAt,
        List<ReconciliationService.BalanceMismatch> mismatches
) {
    public enum Status {
        CONSISTENT,
        MISMATCHED
    }
}
