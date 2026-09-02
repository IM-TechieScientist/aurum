package dev.aurum;

import dev.aurum.account.AccountService;
import dev.aurum.account.AccountStatus;
import dev.aurum.account.AccountView;
import dev.aurum.common.ApiException;
import dev.aurum.ledger.LedgerService;
import dev.aurum.ledger.TransactionView;
import dev.aurum.reconciliation.ReconciliationService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class LedgerPropertyTest {

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;
    private static AccountService accounts;
    private static LedgerService ledger;
    private static ReconciliationService reconciliation;
    private static JdbcTemplate jdbc;

    @BeforeContainer
    static void startApplication() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("aurum_properties")
                .withUsername("aurum")
                .withPassword("aurum");
        postgres.start();
        context = new SpringApplicationBuilder(AurumApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN")
                .run();
        accounts = context.getBean(AccountService.class);
        ledger = context.getBean(LedgerService.class);
        reconciliation = context.getBean(ReconciliationService.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterContainer
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Property(tries = 20, shrinking = ShrinkingMode.OFF)
    void generatedOperationSequencesPreserveLedgerInvariants(
            @ForAll("operationSequences") List<GeneratedOperation> operations) {
        AccountView first = accounts.create("Property first " + key(), "INR");
        AccountView second = accounts.create("Property second " + key(), "INR");
        Model model = new Model(first.id(), second.id());
        ledger.fund(first.id(), 500, "INR", "property seed", key());
        ledger.fund(second.id(), 500, "INR", "property seed", key());
        model.firstBalance = 500;
        model.secondBalance = 500;

        for (GeneratedOperation operation : operations) {
            apply(operation, model);
            assertInvariants(model);
        }
    }

    @Provide
    Arbitrary<List<GeneratedOperation>> operationSequences() {
        Arbitrary<OperationKind> kinds = Arbitraries.of(OperationKind.values());
        Arbitrary<Integer> amounts = Arbitraries.integers().between(1, 250);
        return Combinators.combine(kinds, amounts)
                .as(GeneratedOperation::new)
                .list().ofMinSize(5).ofMaxSize(20);
    }

    private void apply(GeneratedOperation operation, Model model) {
        long amount = operation.amountMinor();
        switch (operation.kind()) {
            case FUND_FIRST -> {
                TransactionView transaction = ledger.fund(
                        model.firstId, amount, "INR", null, key());
                model.firstBalance += amount;
                model.posted.add(new Posted(transaction.id(), amount, 0));
            }
            case FUND_SECOND -> {
                TransactionView transaction = ledger.fund(
                        model.secondId, amount, "INR", null, key());
                model.secondBalance += amount;
                model.posted.add(new Posted(transaction.id(), 0, amount));
            }
            case WITHDRAW_FIRST -> withdraw(model, true, amount);
            case WITHDRAW_SECOND -> withdraw(model, false, amount);
            case TRANSFER_FIRST_TO_SECOND -> transfer(model, true, amount);
            case TRANSFER_SECOND_TO_FIRST -> transfer(model, false, amount);
            case FREEZE_FIRST -> {
                accounts.changeStatus(model.firstId, AccountStatus.FROZEN);
                model.firstFrozen = true;
            }
            case UNFREEZE_FIRST -> {
                accounts.changeStatus(model.firstId, AccountStatus.ACTIVE);
                model.firstFrozen = false;
            }
            case FREEZE_SECOND -> {
                accounts.changeStatus(model.secondId, AccountStatus.FROZEN);
                model.secondFrozen = true;
            }
            case UNFREEZE_SECOND -> {
                accounts.changeStatus(model.secondId, AccountStatus.ACTIVE);
                model.secondFrozen = false;
            }
            case REVERSE_LATEST -> reverseLatest(model);
        }
    }

    private void withdraw(Model model, boolean firstAccount, long amount) {
        UUID accountId = firstAccount ? model.firstId : model.secondId;
        long balance = firstAccount ? model.firstBalance : model.secondBalance;
        boolean frozen = firstAccount ? model.firstFrozen : model.secondFrozen;
        if (frozen) {
            expectCode(() -> ledger.withdraw(accountId, amount, "INR", null, key()),
                    "ACCOUNT_FROZEN");
            return;
        }
        if (balance < amount) {
            expectCode(() -> ledger.withdraw(accountId, amount, "INR", null, key()),
                    "INSUFFICIENT_FUNDS");
            return;
        }
        TransactionView transaction = ledger.withdraw(accountId, amount, "INR", null, key());
        if (firstAccount) {
            model.firstBalance -= amount;
            model.posted.add(new Posted(transaction.id(), -amount, 0));
        } else {
            model.secondBalance -= amount;
            model.posted.add(new Posted(transaction.id(), 0, -amount));
        }
    }

    private void transfer(Model model, boolean firstToSecond, long amount) {
        UUID sourceId = firstToSecond ? model.firstId : model.secondId;
        UUID destinationId = firstToSecond ? model.secondId : model.firstId;
        long sourceBalance = firstToSecond ? model.firstBalance : model.secondBalance;
        boolean sourceFrozen = firstToSecond ? model.firstFrozen : model.secondFrozen;
        if (sourceFrozen) {
            expectCode(() -> ledger.transfer(
                    sourceId, destinationId, amount, "INR", null, key()), "ACCOUNT_FROZEN");
            return;
        }
        if (sourceBalance < amount) {
            expectCode(() -> ledger.transfer(
                    sourceId, destinationId, amount, "INR", null, key()), "INSUFFICIENT_FUNDS");
            return;
        }
        TransactionView transaction = ledger.transfer(
                sourceId, destinationId, amount, "INR", null, key());
        if (firstToSecond) {
            model.firstBalance -= amount;
            model.secondBalance += amount;
            model.posted.add(new Posted(transaction.id(), -amount, amount));
        } else {
            model.firstBalance += amount;
            model.secondBalance -= amount;
            model.posted.add(new Posted(transaction.id(), amount, -amount));
        }
    }

    private void reverseLatest(Model model) {
        Posted posted = null;
        for (int index = model.posted.size() - 1; index >= 0; index--) {
            if (!model.posted.get(index).reversed) {
                posted = model.posted.get(index);
                break;
            }
        }
        if (posted == null) {
            return;
        }
        long rebuiltFirst = model.firstBalance - posted.firstDelta;
        long rebuiltSecond = model.secondBalance - posted.secondDelta;
        if (rebuiltFirst < 0 || rebuiltSecond < 0) {
            Posted nonReversible = posted;
            expectCode(() -> ledger.reverse(
                    nonReversible.transactionId, "property reversal", key()), "INSUFFICIENT_FUNDS");
            return;
        }
        ledger.reverse(posted.transactionId, "property reversal", key());
        posted.reversed = true;
        model.firstBalance = rebuiltFirst;
        model.secondBalance = rebuiltSecond;
    }

    private void assertInvariants(Model model) {
        assertThat(accounts.get(model.firstId).balanceMinor()).isEqualTo(model.firstBalance);
        assertThat(accounts.get(model.secondId).balanceMinor()).isEqualTo(model.secondBalance);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT t.id
                      FROM ledger_transaction t
                      JOIN ledger_entry e ON e.transaction_id = t.id
                     GROUP BY t.id
                    HAVING COUNT(*) < 2
                        OR COUNT(DISTINCT e.currency) <> 1
                        OR COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0)
                           <> COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0)
                ) invalid
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM account a
                  JOIN account_balance b ON b.account_id = a.id
                 WHERE a.account_type = 'CUSTOMER' AND b.balance_minor < 0
                """, Long.class)).isZero();
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    private void expectCode(ThrowingOperation operation, String expectedCode) {
        try {
            operation.run();
            fail("Expected operation to fail with " + expectedCode);
        } catch (ApiException exception) {
            assertThat(exception.code()).isEqualTo(expectedCode);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private enum OperationKind {
        FUND_FIRST,
        FUND_SECOND,
        WITHDRAW_FIRST,
        WITHDRAW_SECOND,
        TRANSFER_FIRST_TO_SECOND,
        TRANSFER_SECOND_TO_FIRST,
        FREEZE_FIRST,
        UNFREEZE_FIRST,
        FREEZE_SECOND,
        UNFREEZE_SECOND,
        REVERSE_LATEST
    }

    private record GeneratedOperation(OperationKind kind, int amountMinor) {
    }

    private static final class Model {
        private final UUID firstId;
        private final UUID secondId;
        private final List<Posted> posted = new ArrayList<>();
        private long firstBalance;
        private long secondBalance;
        private boolean firstFrozen;
        private boolean secondFrozen;

        private Model(UUID firstId, UUID secondId) {
            this.firstId = firstId;
            this.secondId = secondId;
        }
    }

    private static final class Posted {
        private final UUID transactionId;
        private final long firstDelta;
        private final long secondDelta;
        private boolean reversed;

        private Posted(UUID transactionId, long firstDelta, long secondDelta) {
            this.transactionId = transactionId;
            this.firstDelta = firstDelta;
            this.secondDelta = secondDelta;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
