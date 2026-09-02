package dev.aurum.observability;

import dev.aurum.common.PostgresTransactionRetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class AurumMetrics {

    private final MeterRegistry registry;
    private final AtomicLong lastReconciliationMismatches = new AtomicLong();

    public AurumMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("aurum.reconciliation.last.mismatches", lastReconciliationMismatches, AtomicLong::get)
                .description("Mismatch count observed by the last completed reconciliation")
                .register(registry);
    }

    public Timer.Sample startTransfer() {
        return Timer.start(registry);
    }

    public void finishTransfer(Timer.Sample sample, TransferOutcome outcome) {
        Counter.builder("aurum.transfer.operations")
                .description("Transfer calls by final outcome")
                .tag("outcome", outcome.metricValue)
                .register(registry)
                .increment();
        sample.stop(Timer.builder("aurum.transfer.duration")
                .description("End-to-end transfer duration including retries")
                .tag("outcome", outcome.metricValue)
                .register(registry));
    }

    public void recordTransferRetry(PostgresTransactionRetry.RetryReason reason) {
        Counter.builder("aurum.transfer.retries")
                .description("Retried transfer transactions by PostgreSQL failure reason")
                .tag("reason", reason.metricValue())
                .register(registry)
                .increment();
    }

    public void recordIdempotency(IdempotencyOutcome outcome) {
        Counter.builder("aurum.idempotency.requests")
                .description("Idempotency claim decisions")
                .tag("outcome", outcome.metricValue)
                .register(registry)
                .increment();
    }

    public Timer.Sample startReconciliation() {
        return Timer.start(registry);
    }

    public void finishReconciliation(Timer.Sample sample, ReconciliationOutcome outcome,
                                     Integer mismatchCount) {
        Counter.builder("aurum.reconciliation.runs")
                .description("Scheduled reconciliation attempts by outcome")
                .tag("outcome", outcome.metricValue)
                .register(registry)
                .increment();
        sample.stop(Timer.builder("aurum.reconciliation.duration")
                .description("Scheduled reconciliation attempt duration")
                .tag("outcome", outcome.metricValue)
                .register(registry));
        if (mismatchCount != null) {
            lastReconciliationMismatches.set(mismatchCount);
        }
    }

    public enum TransferOutcome {
        SUCCESS("success"),
        BUSINESS_FAILURE("business_failure"),
        SYSTEM_FAILURE("system_failure");

        private final String metricValue;

        TransferOutcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }

    public enum IdempotencyOutcome {
        CLAIMED("claimed"),
        REPLAYED("replayed"),
        CONFLICT("conflict"),
        INCOMPLETE("incomplete");

        private final String metricValue;

        IdempotencyOutcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }

    public enum ReconciliationOutcome {
        CONSISTENT("consistent"),
        MISMATCHED("mismatched"),
        SKIPPED("skipped"),
        ERROR("error");

        private final String metricValue;

        ReconciliationOutcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }
}
