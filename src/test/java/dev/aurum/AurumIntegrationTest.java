package dev.aurum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aurum.account.AccountService;
import dev.aurum.account.AccountStatus;
import dev.aurum.account.AccountView;
import dev.aurum.common.ApiException;
import dev.aurum.ledger.LedgerService;
import dev.aurum.ledger.TransactionSummary;
import dev.aurum.ledger.TransactionView;
import dev.aurum.reconciliation.ReconciliationJob;
import dev.aurum.reconciliation.ReconciliationRunService;
import dev.aurum.reconciliation.ReconciliationRunView;
import dev.aurum.reconciliation.ReconciliationService;
import dev.aurum.reliability.FailureProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@AutoConfigureMockMvc
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
        registry.add("debug", () -> false);
    }

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationJob reconciliationJob;

    @Autowired
    ReconciliationRunService reconciliationRuns;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    FailureProbe failureProbe;

    @Test
    void durableUsersOwnAccountsAndCustomersCannotCrossOwnershipBoundaries() throws Exception {
        String username = "customer-" + key().substring(0, 8);
        String password = "strong-local-password";
        String userBody = mockMvc.perform(post("/api/v1/users")
                        .with(httpBasic("admin", "admin-local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","role":"CUSTOMER"}
                                """.formatted(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(objectMapper.readTree(userBody).get("id").asText());

        String accountBody = mockMvc.perform(post("/api/v1/accounts")
                        .with(httpBasic("operator", "operator-local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"Owned customer","ownerUsername":"%s","currency":"INR"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(userId.toString()))
                .andExpect(jsonPath("$.ownerUsername").value(username))
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(objectMapper.readTree(accountBody).get("id").asText());

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .with(httpBasic(username, password)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .with(httpBasic("customer", "customer-local")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        AccountView defaultDestination = account("Default destination", "INR");
        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s",
                                 "amountMinor":1,"currency":"INR"}
                                """.formatted(accountId, defaultDestination.id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/users/{id}/role", userId)
                        .with(httpBasic("admin", "admin-local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_OWNS_ACCOUNTS"));
    }

    @Test
    void closedAccountsAreZeroBalanceOnlyAndCannotBeReopenedOrPostedTo() throws Exception {
        AccountView funded = account("Cannot close funded", "INR");
        ledger.fund(funded.id(), 100, "INR", null, key());
        mockMvc.perform(patch("/api/v1/accounts/{id}/close", funded.id())
                        .with(httpBasic("operator", "operator-local")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EMPTY"));

        AccountView closed = account("Closed account", "INR");
        mockMvc.perform(patch("/api/v1/accounts/{id}/close", closed.id())
                        .with(httpBasic("operator", "operator-local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(patch("/api/v1/accounts/{id}/unfreeze", closed.id())
                        .with(httpBasic("operator", "operator-local")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        mockMvc.perform(post("/api/v1/accounts/{id}/fund", closed.id())
                        .with(httpBasic("operator", "operator-local"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":100,\"currency\":\"INR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        assertThat(accounts.get(closed.id()).balanceMinor()).isZero();
    }

    @Test
    void auditEventsCaptureActorsAndAreAppendOnly() throws Exception {
        AccountView source = account("Audited source", "INR");
        AccountView destination = account("Audited destination", "INR");
        ledger.fund(source.id(), 200, "INR", null, key());
        String idempotencyKey = key();
        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s",
                                 "amountMinor":50,"currency":"INR"}
                                """.formatted(source.id(), destination.id())))
                .andExpect(status().isCreated());

        String auditBody = mockMvc.perform(get("/api/v1/audit-events?limit=100")
                        .with(httpBasic("auditor", "auditor-local")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = objectMapper.readTree(auditBody);
        assertThat(events.findValuesAsText("action")).contains("TRANSFER");
        assertThat(events.findValuesAsText("actorUsername")).contains("customer");
        long eventId = jdbc.queryForObject("""
                SELECT id FROM audit_event
                 WHERE action = 'TRANSFER' AND correlation_id = ?
                """, Long.class, idempotencyKey);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_event SET target_type = 'CHANGED' WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class);

        String roleUsername = "role-" + key().substring(0, 8);
        String roleUserBody = mockMvc.perform(post("/api/v1/users")
                        .with(httpBasic("admin", "admin-local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"strong-local-password","role":"AUDITOR"}
                                """.formatted(roleUsername)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID roleUserId = UUID.fromString(objectMapper.readTree(roleUserBody).get("id").asText());
        mockMvc.perform(patch("/api/v1/users/{id}/role", roleUserId)
                        .with(httpBasic("admin", "admin-local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                 WHERE action = 'CHANGE_USER_ROLE' AND target_id = ?
                """, Long.class, roleUserId.toString())).isEqualTo(1);

        mockMvc.perform(get("/api/v1/audit-events")
                        .with(httpBasic("customer", "customer-local")))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionBoundaryFailuresRollBackAndAllowSafeRetry() {
        for (FailureProbe.FailurePoint point : List.of(
                FailureProbe.FailurePoint.AFTER_TRANSACTION_INSERT,
                FailureProbe.FailurePoint.AFTER_LEDGER_ENTRIES_INSERTED,
                FailureProbe.FailurePoint.BEFORE_COMMIT)) {
            AccountView source = account("Fault source " + point, "INR");
            AccountView destination = account("Fault destination " + point, "INR");
            ledger.fund(source.id(), 500, "INR", null, key());
            String idempotencyKey = key();
            long transactionsBefore = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_transaction", Long.class);
            long auditEventsBefore = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event", Long.class);

            doThrow(new InjectedFailure(point)).when(failureProbe).check(point);
            assertThatThrownBy(() -> ledger.transfer(source.id(), destination.id(), 100,
                    "INR", "fault injection", idempotencyKey)).isInstanceOf(RuntimeException.class);
            reset(failureProbe);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class))
                    .isEqualTo(transactionsBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class))
                    .isEqualTo(auditEventsBefore);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM idempotency_record
                     WHERE scope = 'transfer' AND idempotency_key = ?
                    """, Long.class, idempotencyKey)).isZero();
            assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(500);
            assertThat(accounts.get(destination.id()).balanceMinor()).isZero();

            TransactionView retried = ledger.transfer(source.id(), destination.id(), 100,
                    "INR", "fault injection", idempotencyKey);
            assertThat(retried.type()).isEqualTo(dev.aurum.ledger.TransactionType.TRANSFER);
            assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(400);
            assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(100);
        }
    }

    @Test
    void lostHttpResponseReplaysAlreadyCommittedTransfer() throws Exception {
        AccountView source = account("Lost response source", "INR");
        AccountView destination = account("Lost response destination", "INR");
        ledger.fund(source.id(), 500, "INR", null, key());
        String idempotencyKey = key();
        String request = """
                {"sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinor":100,"currency":"INR","reference":"lost response"}
                """.formatted(source.id(), destination.id());

        doThrow(new InjectedFailure(FailureProbe.FailurePoint.AFTER_COMMIT_BEFORE_RESPONSE))
                .when(failureProbe).check(FailureProbe.FailurePoint.AFTER_COMMIT_BEFORE_RESPONSE);
        assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/transfers")
                .with(httpBasic("customer", "customer-local"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))).isInstanceOf(Exception.class);
        reset(failureProbe);

        UUID committedId = jdbc.queryForObject("""
                SELECT transaction_id FROM idempotency_record
                 WHERE scope = 'transfer' AND idempotency_key = ?
                """, UUID.class, idempotencyKey);
        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(committedId.toString()));

        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(400);
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(100);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_transaction WHERE id = ?
                """, Long.class, committedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                 WHERE action = 'TRANSFER' AND correlation_id = ?
                """, Long.class, idempotencyKey)).isEqualTo(1);
    }

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
    void projectionRebuildRepairsOnlyLedgerMismatches() throws Exception {
        AccountView account = account("Projection rebuild", "INR");
        ledger.fund(account.id(), 750, "INR", null, key());
        jdbc.update("UPDATE account_balance SET balance_minor = 13 WHERE account_id = ?", account.id());

        ReconciliationService.ReconciliationResult before = reconciliation.reconcile();
        assertThat(before.consistent()).isFalse();
        assertThat(before.mismatches()).anySatisfy(mismatch -> {
            assertThat(mismatch.accountId()).isEqualTo(account.id());
            assertThat(mismatch.projectedBalanceMinor()).isEqualTo(13);
            assertThat(mismatch.ledgerBalanceMinor()).isEqualTo(750);
        });

        mockMvc.perform(post("/api/v1/reconciliation/rebuild")
                        .with(httpBasic("operator", "operator-local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repairedAccounts").value(1))
                .andExpect(jsonPath("$.repairs[0].accountId").value(account.id().toString()))
                .andExpect(jsonPath("$.repairs[0].previousBalanceMinor").value(13))
                .andExpect(jsonPath("$.repairs[0].rebuiltBalanceMinor").value(750));

        assertThat(accounts.get(account.id()).balanceMinor()).isEqualTo(750);
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void httpTransferContractSupportsReplayAndExposesMetrics() throws Exception {
        AccountView source = account("HTTP source", "INR");
        AccountView destination = account("HTTP destination", "INR");
        ledger.fund(source.id(), 1_000, "INR", null, key());
        String idempotencyKey = key();
        String request = """
                {"sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinor":250,"currency":"INR","reference":"http contract"}
                """.formatted(source.id(), destination.id());
        double transferSuccessBefore = counter("aurum.transfer.operations", "outcome", "success");
        double replayBefore = counter("aurum.idempotency.requests", "outcome", "replayed");

        String firstBody = mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(firstBody);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));

        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(750);
        assertThat(accounts.get(destination.id()).balanceMinor()).isEqualTo(250);
        assertThat(counter("aurum.transfer.operations", "outcome", "success"))
                .isEqualTo(transferSuccessBefore + 2);
        assertThat(counter("aurum.idempotency.requests", "outcome", "replayed"))
                .isEqualTo(replayBefore + 1);

        mockMvc.perform(get("/actuator/metrics/aurum.transfer.operations")
                        .with(httpBasic("auditor", "auditor-local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("aurum.transfer.operations"));
    }

    @Test
    void rbacRequiresAuthenticationAndEnforcesOperatorBoundaries() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/reconciliation/rebuild"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/reconciliation/rebuild")
                        .with(httpBasic("customer", "customer-local")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/reconciliation")
                        .with(httpBasic("auditor", "auditor-local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void scheduledReconciliationPersistsReportsAndMetrics() throws Exception {
        double consistentBefore = counter("aurum.reconciliation.runs", "outcome", "consistent");
        double mismatchedBefore = counter("aurum.reconciliation.runs", "outcome", "mismatched");

        ReconciliationRunService.RunAttempt consistentAttempt = reconciliationJob.runOnce();
        assertThat(consistentAttempt.executed()).isTrue();
        assertThat(consistentAttempt.run().status()).isEqualTo(ReconciliationRunView.Status.CONSISTENT);
        assertThat(consistentAttempt.run().mismatchCount()).isZero();

        AccountView account = account("Scheduled reconciliation", "INR");
        ledger.fund(account.id(), 900, "INR", null, key());
        jdbc.update("UPDATE account_balance SET balance_minor = 12 WHERE account_id = ?", account.id());

        ReconciliationRunService.RunAttempt mismatchedAttempt = reconciliationJob.runOnce();
        assertThat(mismatchedAttempt.executed()).isTrue();
        assertThat(mismatchedAttempt.run().status()).isEqualTo(ReconciliationRunView.Status.MISMATCHED);
        assertThat(mismatchedAttempt.run().mismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.accountId()).isEqualTo(account.id());
            assertThat(mismatch.projectedBalanceMinor()).isEqualTo(12);
            assertThat(mismatch.ledgerBalanceMinor()).isEqualTo(900);
        });
        assertThat(reconciliationRuns.recent(20))
                .extracting(ReconciliationRunView::id)
                .contains(consistentAttempt.run().id(), mismatchedAttempt.run().id());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reconciliation_run SET mismatch_count = 0 WHERE id = ?",
                mismatchedAttempt.run().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM reconciliation_run_mismatch WHERE run_id = ?",
                mismatchedAttempt.run().id()))
                .isInstanceOf(DataAccessException.class);

        String historyBody = mockMvc.perform(get("/api/v1/reconciliation/runs?limit=20")
                        .with(httpBasic("auditor", "auditor-local")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode history = objectMapper.readTree(historyBody);
        assertThat(history.findValuesAsText("id"))
                .contains(consistentAttempt.run().id().toString(), mismatchedAttempt.run().id().toString());

        mockMvc.perform(get("/api/v1/reconciliation/runs")
                        .with(httpBasic("customer", "customer-local")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(counter("aurum.reconciliation.runs", "outcome", "consistent"))
                .isEqualTo(consistentBefore + 1);
        assertThat(counter("aurum.reconciliation.runs", "outcome", "mismatched"))
                .isEqualTo(mismatchedBefore + 1);
        assertThat(gauge("aurum.reconciliation.last.mismatches")).isEqualTo(1);

        reconciliation.rebuild();
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void scheduledReconciliationSkipsWhenAnotherInstanceHoldsTheLock() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null,
                    ReconciliationRunService.ADVISORY_LOCK_KEY);
            lockHeld.countDown();
            try {
                releaseLock.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding reconciliation lock", exception);
            }
        }));

        try {
            lockHeld.await();
            double skippedBefore = counter("aurum.reconciliation.runs", "outcome", "skipped");
            ReconciliationRunService.RunAttempt attempt = reconciliationJob.runOnce();
            assertThat(attempt.executed()).isFalse();
            assertThat(attempt.run()).isNull();
            assertThat(counter("aurum.reconciliation.runs", "outcome", "skipped"))
                    .isEqualTo(skippedBefore + 1);
        } finally {
            releaseLock.countDown();
            holder.get();
            executor.shutdownNow();
        }
    }

    @Test
    void httpValidationAndIdempotencyConflictsUseProblemDetails() throws Exception {
        AccountView source = account("HTTP error source", "USD");
        AccountView destination = account("HTTP error destination", "USD");
        ledger.fund(source.id(), 500, "USD", null, key());
        String idempotencyKey = key();
        String validRequest = """
                {"sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinor":100,"currency":"USD"}
                """.formatted(source.id(), destination.id());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest.replace("\"amountMinor\":100", "\"amountMinor\":0")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.amountMinor").exists());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(httpBasic("customer", "customer-local"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest.replace("\"amountMinor\":100", "\"amountMinor\":200")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(accounts.get(source.id()).balanceMinor()).isEqualTo(400);
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
    void accountHistoryUsesStableKeysetPagination() {
        AccountView source = account("History source", "INR");
        AccountView destination = account("History destination", "INR");
        ledger.fund(source.id(), 1_000, "INR", "history funding", key());
        ledger.transfer(source.id(), destination.id(), 100, "INR", "history one", key());
        ledger.transfer(source.id(), destination.id(), 100, "INR", "history two", key());
        ledger.transfer(source.id(), destination.id(), 100, "INR", "history three", key());

        List<TransactionSummary> complete = ledger.history(source.id(), null, 10);
        List<TransactionSummary> firstPage = ledger.history(source.id(), null, 2);
        List<TransactionSummary> secondPage = ledger.history(
                source.id(), firstPage.getLast().id(), 2);

        assertThat(complete).hasSize(4);
        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(2);
        assertThat(Stream.concat(firstPage.stream(), secondPage.stream()).map(TransactionSummary::id))
                .containsExactlyElementsOf(complete.stream().map(TransactionSummary::id).toList());
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

    private double counter(String name, String tagName, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagName, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(FailureProbe.FailurePoint point) {
            super("Injected failure at " + point);
        }
    }
}
