package dev.aurum.ledger;

import dev.aurum.account.AccountRepository;
import dev.aurum.account.AccountStatus;
import dev.aurum.account.AccountType;
import dev.aurum.account.AccountView;
import dev.aurum.account.EntryDirection;
import dev.aurum.common.ApiException;
import dev.aurum.common.PostgresTransactionRetry;
import dev.aurum.common.RequestHash;
import dev.aurum.idempotency.IdempotencyService;
import dev.aurum.observability.AurumMetrics;
import dev.aurum.audit.AuditAction;
import dev.aurum.audit.AuditService;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final PostingService posting;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactions;
    private final PostgresTransactionRetry retries;
    private final AurumMetrics metrics;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();

    public LedgerService(AccountRepository accounts, LedgerRepository ledger,
                         PostingService posting, IdempotencyService idempotency,
                         TransactionTemplate transactions, PostgresTransactionRetry retries,
                         AurumMetrics metrics, AuditService audit) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.retries = retries;
        this.metrics = metrics;
        this.audit = audit;
    }

    @Transactional
    public TransactionView fund(UUID accountId, long amountMinor, String requestedCurrency,
                                String reference, String idempotencyKey) {
        String currency = normalizeCurrency(requestedCurrency);
        String scope = "fund:" + accountId;
        String hash = RequestHash.sha256(accountId + "|" + amountMinor + "|" + currency + "|" + safe(reference));
        Instant now = Instant.now(clock);
        IdempotencyService.Claim claim = idempotency.claim(scope, idempotencyKey, hash, now);
        if (!claim.owned()) {
            return requiredTransaction(claim.transactionId());
        }

        AccountView target = requiredAccount(accountId);
        if (target.accountType() != AccountType.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT_TYPE",
                    "Only customer accounts can be funded");
        }
        requireCurrency(target, currency);
        AccountView settlement = accounts.findSettlement(currency).orElseThrow(() ->
                new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY",
                        "No settlement account exists for this currency"));

        Map<UUID, AccountView> locked = posting.lockAccounts(List.of(settlement.id(), target.id()));
        requireOpen(locked.get(target.id()), "A closed account cannot receive funding");
        TransactionView result = posting.post(TransactionType.FUNDING, reference, null, List.of(
                new LedgerEntryDraft(settlement.id(), EntryDirection.DEBIT, amountMinor, currency),
                new LedgerEntryDraft(target.id(), EntryDirection.CREDIT, amountMinor, currency)
        ), locked, now);
        idempotency.complete(scope, idempotencyKey, result.id());
        audit.record(AuditAction.FUND, "TRANSACTION", result.id(), idempotencyKey);
        return result;
    }

    @Transactional
    public TransactionView withdraw(UUID accountId, long amountMinor, String requestedCurrency,
                                    String reference, String idempotencyKey) {
        String currency = normalizeCurrency(requestedCurrency);
        String scope = "withdraw:" + accountId;
        String hash = RequestHash.sha256(accountId + "|" + amountMinor + "|" + currency + "|" + safe(reference));
        Instant now = Instant.now(clock);
        IdempotencyService.Claim claim = idempotency.claim(scope, idempotencyKey, hash, now);
        if (!claim.owned()) {
            return requiredTransaction(claim.transactionId());
        }

        AccountView source = requiredAccount(accountId);
        requireCustomerAccount(source);
        requireCurrency(source, currency);
        AccountView settlement = accounts.findSettlement(currency).orElseThrow(() ->
                new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY",
                        "No settlement account exists for this currency"));

        Map<UUID, AccountView> locked = posting.lockAccounts(List.of(source.id(), settlement.id()));
        source = locked.get(source.id());
        if (source.status() == AccountStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED",
                    "A closed account cannot withdraw funds");
        }
        if (source.status() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_FROZEN",
                    "A frozen account cannot withdraw funds");
        }

        TransactionView result = posting.post(TransactionType.WITHDRAWAL, reference, null, List.of(
                new LedgerEntryDraft(source.id(), EntryDirection.DEBIT, amountMinor, currency),
                new LedgerEntryDraft(settlement.id(), EntryDirection.CREDIT, amountMinor, currency)
        ), locked, now);
        idempotency.complete(scope, idempotencyKey, result.id());
        audit.record(AuditAction.WITHDRAW, "TRANSACTION", result.id(), idempotencyKey);
        return result;
    }

    public TransactionView transfer(UUID sourceAccountId, UUID destinationAccountId,
                                    long amountMinor, String requestedCurrency,
                                    String reference, String idempotencyKey) {
        Timer.Sample sample = metrics.startTransfer();
        AurumMetrics.TransferOutcome outcome = AurumMetrics.TransferOutcome.SUCCESS;
        try {
            return retries.execute(
                    () -> transactions.execute(status -> transferOnce(
                            sourceAccountId, destinationAccountId, amountMinor,
                            requestedCurrency, reference, idempotencyKey)),
                    metrics::recordTransferRetry);
        } catch (ApiException exception) {
            outcome = AurumMetrics.TransferOutcome.BUSINESS_FAILURE;
            throw exception;
        } catch (RuntimeException exception) {
            outcome = AurumMetrics.TransferOutcome.SYSTEM_FAILURE;
            throw exception;
        } finally {
            metrics.finishTransfer(sample, outcome);
        }
    }

    private TransactionView transferOnce(UUID sourceAccountId, UUID destinationAccountId,
                                         long amountMinor, String requestedCurrency,
                                         String reference, String idempotencyKey) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER",
                    "Source and destination accounts must be different");
        }
        String currency = normalizeCurrency(requestedCurrency);
        String scope = "transfer";
        String hash = RequestHash.sha256(sourceAccountId + "|" + destinationAccountId + "|"
                + amountMinor + "|" + currency + "|" + safe(reference));
        Instant now = Instant.now(clock);
        IdempotencyService.Claim claim = idempotency.claim(scope, idempotencyKey, hash, now);
        if (!claim.owned()) {
            return requiredTransaction(claim.transactionId());
        }

        Map<UUID, AccountView> locked = posting.lockAccounts(List.of(sourceAccountId, destinationAccountId));
        AccountView source = locked.get(sourceAccountId);
        AccountView destination = locked.get(destinationAccountId);
        requireCustomerAccount(source);
        requireCustomerAccount(destination);
        requireCurrency(source, currency);
        requireCurrency(destination, currency);
        if (source.status() == AccountStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED",
                    "A closed account cannot send funds");
        }
        requireOpen(destination, "A closed account cannot receive funds");
        if (source.status() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_FROZEN",
                    "A frozen account cannot send funds");
        }

        TransactionView result = posting.post(TransactionType.TRANSFER, reference, null, List.of(
                new LedgerEntryDraft(source.id(), EntryDirection.DEBIT, amountMinor, currency),
                new LedgerEntryDraft(destination.id(), EntryDirection.CREDIT, amountMinor, currency)
        ), locked, now);
        idempotency.complete(scope, idempotencyKey, result.id());
        audit.record(AuditAction.TRANSFER, "TRANSACTION", result.id(), idempotencyKey);
        return result;
    }

    @Transactional
    public TransactionView reverse(UUID originalTransactionId, String reason, String idempotencyKey) {
        String scope = "reversal:" + originalTransactionId;
        String hash = RequestHash.sha256(originalTransactionId + "|" + safe(reason));
        Instant now = Instant.now(clock);
        IdempotencyService.Claim claim = idempotency.claim(scope, idempotencyKey, hash, now);
        if (!claim.owned()) {
            return requiredTransaction(claim.transactionId());
        }

        TransactionView original = requiredTransaction(originalTransactionId);
        if (original.type() == TransactionType.REVERSAL) {
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_OF_REVERSAL",
                    "A reversal transaction cannot itself be reversed");
        }
        if (ledger.findReversal(originalTransactionId).isPresent()) {
            throw alreadyReversed();
        }

        List<UUID> accountIds = original.entries().stream().map(LedgerEntryView::accountId).toList();
        Map<UUID, AccountView> locked = posting.lockAccounts(accountIds);
        locked.values().stream()
                .filter(account -> account.accountType() == AccountType.CUSTOMER)
                .forEach(account -> requireOpen(account,
                        "A transaction involving a closed account cannot be reversed"));
        if (ledger.findReversal(originalTransactionId).isPresent()) {
            throw alreadyReversed();
        }

        List<LedgerEntryDraft> compensatingEntries = new ArrayList<>();
        for (LedgerEntryView entry : original.entries()) {
            compensatingEntries.add(new LedgerEntryDraft(
                    entry.accountId(), entry.direction().opposite(), entry.amountMinor(), entry.currency()));
        }
        TransactionView result = posting.post(TransactionType.REVERSAL, reason, originalTransactionId,
                compensatingEntries, locked, now);
        idempotency.complete(scope, idempotencyKey, result.id());
        audit.record(AuditAction.REVERSAL, "TRANSACTION", result.id(), idempotencyKey);
        return result;
    }

    @Transactional(readOnly = true)
    public TransactionView get(UUID transactionId) {
        return requiredTransaction(transactionId);
    }

    @Transactional(readOnly = true)
    public List<TransactionSummary> history(UUID accountId, UUID before, int limit) {
        requiredAccount(accountId);
        return ledger.history(accountId, before, limit);
    }

    private AccountView requiredAccount(UUID accountId) {
        return accounts.find(accountId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found"));
    }

    private TransactionView requiredTransaction(UUID transactionId) {
        return ledger.find(transactionId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction was not found"));
    }

    private void requireCustomerAccount(AccountView account) {
        if (account.accountType() != AccountType.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT_TYPE",
                    "Transfers require customer accounts");
        }
    }

    private void requireCurrency(AccountView account, String currency) {
        if (!account.currency().equals(currency)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                    "Account and request currencies do not match");
        }
    }

    private void requireOpen(AccountView account, String message) {
        if (account.status() == AccountStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED", message);
        }
    }

    private String normalizeCurrency(String currency) {
        return currency.toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private ApiException alreadyReversed() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED",
                "The transaction has already been reversed");
    }
}
