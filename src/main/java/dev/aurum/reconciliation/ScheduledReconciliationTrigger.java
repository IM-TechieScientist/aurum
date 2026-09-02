package dev.aurum.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "aurum.reconciliation.schedule.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ScheduledReconciliationTrigger {

    private final ReconciliationJob job;

    public ScheduledReconciliationTrigger(ReconciliationJob job) {
        this.job = job;
    }

    @Scheduled(
            cron = "${aurum.reconciliation.schedule.cron:0 0 * * * *}",
            zone = "${aurum.reconciliation.schedule.zone:UTC}")
    void reconcile() {
        try {
            job.runOnce();
        } catch (RuntimeException ignored) {
            // ReconciliationJob records and logs the failure; the scheduler must remain live.
        }
    }
}
