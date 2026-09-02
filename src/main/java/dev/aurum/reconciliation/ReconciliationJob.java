package dev.aurum.reconciliation;

import dev.aurum.observability.AurumMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationRunService runs;
    private final AurumMetrics metrics;

    public ReconciliationJob(ReconciliationRunService runs, AurumMetrics metrics) {
        this.runs = runs;
        this.metrics = metrics;
    }

    public ReconciliationRunService.RunAttempt runOnce() {
        Timer.Sample sample = metrics.startReconciliation();
        try {
            ReconciliationRunService.RunAttempt attempt = runs.execute();
            if (!attempt.executed()) {
                metrics.finishReconciliation(sample, AurumMetrics.ReconciliationOutcome.SKIPPED, null);
                LOGGER.info("Skipped reconciliation because another application instance holds the lock");
                return attempt;
            }

            ReconciliationRunView run = attempt.run();
            AurumMetrics.ReconciliationOutcome outcome = run.status() == ReconciliationRunView.Status.CONSISTENT
                    ? AurumMetrics.ReconciliationOutcome.CONSISTENT
                    : AurumMetrics.ReconciliationOutcome.MISMATCHED;
            metrics.finishReconciliation(sample, outcome, run.mismatchCount());
            if (run.mismatchCount() == 0) {
                LOGGER.info("Reconciliation {} scanned {} accounts with no mismatches",
                        run.id(), run.accountsScanned());
            } else {
                LOGGER.warn("Reconciliation {} found {} mismatches across {} accounts",
                        run.id(), run.mismatchCount(), run.accountsScanned());
            }
            return attempt;
        } catch (RuntimeException exception) {
            metrics.finishReconciliation(sample, AurumMetrics.ReconciliationOutcome.ERROR, null);
            LOGGER.error("Scheduled reconciliation failed", exception);
            throw exception;
        }
    }
}
