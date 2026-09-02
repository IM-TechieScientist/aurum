package dev.aurum.idempotency;

import dev.aurum.common.ApiException;
import dev.aurum.observability.AurumMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final AurumMetrics metrics;

    public IdempotencyService(JdbcTemplate jdbc, AurumMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public Claim claim(String scope, String key, String requestHash, Instant now) {
        validateKey(key);
        int inserted = jdbc.update("""
                INSERT INTO idempotency_record
                    (scope, idempotency_key, request_hash, transaction_id, created_at)
                VALUES (?, ?, ?, NULL, ?)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                """, scope, key, requestHash, Timestamp.from(now));

        if (inserted == 1) {
            metrics.recordIdempotency(AurumMetrics.IdempotencyOutcome.CLAIMED);
            return new Claim(true, null);
        }

        List<StoredClaim> stored = jdbc.query("""
                SELECT request_hash, transaction_id
                  FROM idempotency_record
                 WHERE scope = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> new StoredClaim(
                resultSet.getString("request_hash"),
                resultSet.getObject("transaction_id", UUID.class)
        ), scope, key);

        if (stored.isEmpty()) {
            throw new IllegalStateException("Idempotency claim disappeared");
        }
        StoredClaim existing = stored.getFirst();
        if (!existing.requestHash().equals(requestHash)) {
            metrics.recordIdempotency(AurumMetrics.IdempotencyOutcome.CONFLICT);
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "This idempotency key was already used for a different request");
        }
        if (existing.transactionId() == null) {
            metrics.recordIdempotency(AurumMetrics.IdempotencyOutcome.INCOMPLETE);
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_INCOMPLETE",
                    "The prior request did not complete normally");
        }
        metrics.recordIdempotency(AurumMetrics.IdempotencyOutcome.REPLAYED);
        return new Claim(false, existing.transactionId());
    }

    public void complete(String scope, String key, UUID transactionId) {
        int changed = jdbc.update("""
                UPDATE idempotency_record
                   SET transaction_id = ?
                 WHERE scope = ? AND idempotency_key = ? AND transaction_id IS NULL
                """, transactionId, scope, key);
        if (changed != 1) {
            throw new IllegalStateException("Idempotency claim could not be completed");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain between 1 and 128 characters");
        }
    }

    public record Claim(boolean owned, UUID transactionId) {
    }

    private record StoredClaim(String requestHash, UUID transactionId) {
    }
}
