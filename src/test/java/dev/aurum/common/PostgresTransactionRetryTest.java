package dev.aurum.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTransactionRetryTest {

    @Test
    void retriesDeadlocksAndSerializationFailures() {
        PostgresTransactionRetry retry = new PostgresTransactionRetry(3, 0);
        AtomicInteger attempts = new AtomicInteger();
        List<PostgresTransactionRetry.RetryReason> reasons = new ArrayList<>();

        String result = retry.execute(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw wrappedSqlState("40P01");
            }
            if (attempt == 2) {
                throw wrappedSqlState("40001");
            }
            return "committed";
        }, reasons::add);

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
        assertThat(reasons).containsExactly(
                PostgresTransactionRetry.RetryReason.DEADLOCK,
                PostgresTransactionRetry.RetryReason.SERIALIZATION_FAILURE);
    }

    @Test
    void neverRetriesOtherDatabaseOrBusinessFailures() {
        PostgresTransactionRetry retry = new PostgresTransactionRetry(3, 0);
        RuntimeException nonRetryable = wrappedSqlState("23505");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            attempts.incrementAndGet();
            throw nonRetryable;
        }, reason -> {
            throw new AssertionError("A retry was not expected");
        })).isSameAs(nonRetryable);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void stopsAfterTheConfiguredAttemptLimit() {
        PostgresTransactionRetry retry = new PostgresTransactionRetry(3, 0);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            attempts.incrementAndGet();
            throw wrappedSqlState("40001");
        }, reason -> retries.incrementAndGet())).isInstanceOf(RuntimeException.class);

        assertThat(attempts).hasValue(3);
        assertThat(retries).hasValue(2);
    }

    private RuntimeException wrappedSqlState(String sqlState) {
        return new RuntimeException("translated database failure",
                new SQLException("database aborted transaction", sqlState));
    }
}
