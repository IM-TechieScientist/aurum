package dev.aurum.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class PostgresTransactionRetry {

    static final String DEADLOCK_SQL_STATE = "40P01";
    static final String SERIALIZATION_FAILURE_SQL_STATE = "40001";

    private final int maxAttempts;
    private final long baseDelayMillis;

    public PostgresTransactionRetry(
            @Value("${aurum.postgres-retry.max-attempts:3}") int maxAttempts,
            @Value("${aurum.postgres-retry.base-delay-millis:10}") long baseDelayMillis) {
        if (maxAttempts < 1 || baseDelayMillis < 0) {
            throw new IllegalArgumentException("Invalid PostgreSQL retry configuration");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
    }

    public <T> T execute(Supplier<T> operation, Consumer<RetryReason> onRetry) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                RetryReason reason = retryReason(exception);
                if (reason == null || attempt >= maxAttempts) {
                    throw exception;
                }
                onRetry.accept(reason);
                pauseBeforeRetry();
            }
        }
    }

    private RetryReason retryReason(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                if (DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())) {
                    return RetryReason.DEADLOCK;
                }
                if (SERIALIZATION_FAILURE_SQL_STATE.equals(sqlException.getSQLState())) {
                    return RetryReason.SERIALIZATION_FAILURE;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private void pauseBeforeRetry() {
        if (baseDelayMillis == 0) {
            return;
        }
        long jitter = ThreadLocalRandom.current().nextLong(baseDelayMillis + 1);
        try {
            Thread.sleep(baseDelayMillis + jitter);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a PostgreSQL transaction", exception);
        }
    }

    public enum RetryReason {
        DEADLOCK("deadlock"),
        SERIALIZATION_FAILURE("serialization_failure");

        private final String metricValue;

        RetryReason(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }
}
