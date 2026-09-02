package dev.aurum;

import dev.aurum.account.AccountService;
import dev.aurum.account.AccountStatus;
import dev.aurum.account.AccountView;
import dev.aurum.common.ApiException;
import dev.aurum.ledger.LedgerService;
import dev.aurum.ledger.TransactionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AurumIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aurum")
            .withUsername("aurum")
            .withPassword("aurum");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void transferIsBalancedAndIdempotent() {
        AccountView source = account("Alice", "INR");
        AccountView destination = account("Bob", "INR");
        ledger.fund(source.id(), 10_000, "INR", "initial funding", key());

        String idempotencyKey = key();
        TransactionView first = ledger.transfer(source.id(), destination.id(), 2_500,
                "INR", "invoice-1", idempotencyKey);
        TransactionView replay = ledger.transfer(source.id(), destination.id(), 2_500,
                "INR", "invoice-1", idempotencyKey);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.entries()).hasSize(2);
        assertThat(sum(first, "DEBIT")).isEqualTo(sum(first, "CREDIT"));
        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(7_500);
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction WHERE id = ?",
                Long.class, first.id())).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        AccountView source = account("Idem source", "INR");
        AccountView destination = account("Idem destination", "INR");
        ledger.fund(source.id(), 1_000, "INR", null, key());
        String idempotencyKey = key();

        ledger.transfer(source.id(), destination.id(), 100, "INR", null, idempotencyKey);

        assertThatThrownBy(() -> ledger.transfer(
                source.id(), destination.id(), 200, "INR", null, idempotencyKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(900);
    }

    @Test
    void concurrentDuplicateRequestsCreateOneLogicalTransaction() throws Exception {
        AccountView source = account("Concurrent idem source", "INR");
        AccountView destination = account("Concurrent idem destination", "INR");
        ledger.fund(source.id(), 1_000, "INR", null, key());
        String idempotencyKey = key();
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<UUID>> results = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return ledger.transfer(source.id(), destination.id(), 100,
                            "INR", "concurrent duplicate", idempotencyKey).id();
                }));
            }
            start.countDown();

            List<UUID> transactionIds = new ArrayList<>();
            for (Future<UUID> result : results) {
                transactionIds.add(result.get());
            }
            assertThat(transactionIds).hasSize(attempts).containsOnly(transactionIds.getFirst());

            UUID transactionId = transactionIds.getFirst();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_transaction WHERE id = ?",
                    Long.class, transactionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM idempotency_record
                     WHERE scope = 'transfer' AND idempotency_key = ? AND transaction_id = ?
                    """, Long.class, idempotencyKey, transactionId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(900);
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(100);
        assertThatThrownBy(() -> ledger.transfer(source.id(), destination.id(), 200,
                "INR", "concurrent duplicate", idempotencyKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(900);
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(100);
    }

    @Test
    void withdrawalIsBalancedAndIdempotent() {
        AccountView account = account("Withdrawal account", "INR");
        ledger.fund(account.id(), 10_000, "INR", "initial funding", key());
        String idempotencyKey = key();

        TransactionView first = ledger.withdraw(account.id(), 2_500, "INR",
                "cash withdrawal", idempotencyKey);
        TransactionView replay = ledger.withdraw(account.id(), 2_500, "INR",
                "cash withdrawal", idempotencyKey);

        assertThat(first.type().name()).isEqualTo("WITHDRAWAL");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.entries()).hasSize(2);
        assertThat(sum(first, "DEBIT")).isEqualTo(2_500);
        assertThat(sum(first, "CREDIT")).isEqualTo(2_500);
        assertThat(accounts.get(account.id()).balanceMinor()).isEqualTo(7_500);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_transaction WHERE transaction_type = 'WITHDRAWAL' AND id = ?
                """, Long.class, first.id())).isEqualTo(1);
    }

    @Test
    void insufficientWithdrawalRollsBackEverything() {
        AccountView account = account("Insufficient withdrawal", "INR");
        ledger.fund(account.id(), 500, "INR", null, key());
        String idempotencyKey = key();
        long transactionCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction", Long.class);

        assertThatThrownBy(() -> ledger.withdraw(
                account.id(), 600, "INR", null, idempotencyKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(accounts.get(account.id()).balanceMinor()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class))
                .isEqualTo(transactionCountBefore);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_record
                 WHERE scope = ? AND idempotency_key = ?
                """, Long.class, "withdraw:" + account.id(), idempotencyKey)).isZero();
    }

    @Test
    void withdrawalRejectsFrozenAccountAndCurrencyMismatch() {
        AccountView account = account("Restricted withdrawal", "USD");
        ledger.fund(account.id(), 500, "USD", null, key());
        accounts.changeStatus(account.id(), AccountStatus.FROZEN);

        assertThatThrownBy(() -> ledger.withdraw(account.id(), 100, "USD", null, key()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_FROZEN"));

        accounts.changeStatus(account.id(), AccountStatus.ACTIVE);
        assertThatThrownBy(() -> ledger.withdraw(account.id(), 100, "INR", null, key()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CURRENCY_MISMATCH"));
        assertThat(accounts.get(account.id()).balanceMinor()).isEqualTo(500);
    }

    @Test
    void reversalCompensatesWithoutChangingOriginalEntries() {
        AccountView source = account("Reverse source", "INR");
        AccountView destination = account("Reverse destination", "INR");
        ledger.fund(source.id(), 2_000, "INR", null, key());
        TransactionView original = ledger.transfer(source.id(), destination.id(), 750,
                "INR", "to reverse", key());
        long entryCountBefore = entryCount(original.id());

        TransactionView reversal = ledger.reverse(original.id(), "operator correction", key());

        assertThat(reversal.reversalOf()).isEqualTo(original.id());
        assertThat(entryCount(original.id())).isEqualTo(entryCountBefore);
        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(2_000);
        assertThat(accounts.get(destination.id()).balanceMinor()).isZero();
        assertThatThrownBy(() -> ledger.reverse(original.id(), "again", key()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ALREADY_REVERSED"));
    }

    @Test
    void frozenSourceCannotTransfer() {
        AccountView source = account("Frozen source", "USD");
        AccountView destination = account("Frozen destination", "USD");
        ledger.fund(source.id(), 500, "USD", null, key());
        accounts.changeStatus(source.id(), AccountStatus.FROZEN);

        assertThatThrownBy(() -> ledger.transfer(
                source.id(), destination.id(), 100, "USD", null, key()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_FROZEN"));
        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(500);
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSource() throws Exception {
        AccountView source = account("Hot source", "INR");
        AccountView destination = account("Hot destination", "INR");
        ledger.fund(source.id(), 1_000, "INR", null, key());
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        ledger.transfer(source.id(), destination.id(), 100, "INR", null, key());
                        return true;
                    } catch (ApiException exception) {
                        assertThat(exception.code()).isEqualTo("INSUFFICIENT_FUNDS");
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }

        assertThat(accounts.get(source.id()).balanceMinor()).isZero();
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(1_000);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_transaction t
                JOIN ledger_entry e ON e.transaction_id = t.id
                WHERE t.transaction_type = 'TRANSFER' AND e.account_id = ? AND e.direction = 'DEBIT'
                """, Long.class, source.id())).isEqualTo(10);
    }

    @Test
    void databaseRejectsUnbalancedAndMutableLedgerData() {
        UUID invalidTransactionId = UUID.randomUUID();
        UUID sourceId = account("Constraint source", "INR").id();
        Instant now = Instant.now();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_transaction
                        (id, transaction_type, reference, reversal_of, created_at)
                    VALUES (?, 'TRANSFER', NULL, NULL, ?)
                    """, invalidTransactionId, Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO ledger_entry
                        (id, transaction_id, account_id, direction, amount_minor, currency, created_at)
                    VALUES (?, ?, ?, 'DEBIT', 100, 'INR', ?)
                    """, UUID.randomUUID(), invalidTransactionId, sourceId, Timestamp.from(now));
        })).isInstanceOf(TransactionSystemException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction WHERE id = ?",
                Long.class, invalidTransactionId)).isZero();

        TransactionView funding = ledger.fund(sourceId, 100, "INR", null, key());
        UUID entryId = funding.entries().getFirst().id();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entry SET amount_minor = 101 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class);
    }

    private AccountView account(String ownerName, String currency) {
        return accounts.create(ownerName, currency);
    }

    private long sum(TransactionView transaction, String direction) {
        return transaction.entries().stream()
                .filter(entry -> entry.direction().name().equals(direction))
                .mapToLong(entry -> entry.amountMinor())
                .sum();
    }

    private long entryCount(UUID transactionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE transaction_id = ?",
                Long.class, transactionId);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
