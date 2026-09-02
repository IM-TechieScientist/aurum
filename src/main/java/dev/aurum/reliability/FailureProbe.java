package dev.aurum.reliability;

import org.springframework.stereotype.Component;

@Component
public class FailureProbe {

    public void check(FailurePoint point) {
        // Production no-op. Tests replace this bean to inject deterministic failures.
    }

    public enum FailurePoint {
        AFTER_TRANSACTION_INSERT,
        AFTER_LEDGER_ENTRIES_INSERTED,
        BEFORE_COMMIT,
        AFTER_COMMIT_BEFORE_RESPONSE
    }
}
