package dev.aurum.ledger;

import dev.aurum.account.AccountRepository;
import dev.aurum.account.AccountType;
import dev.aurum.account.AccountView;
import dev.aurum.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PostingService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public PostingService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public Map<UUID, AccountView> lockAccounts(Collection<UUID> accountIds) {
        List<UUID> sortedIds = accountIds.stream().distinct().sorted().toList();
        Map<UUID, AccountView> locked = new LinkedHashMap<>();
        for (UUID accountId : sortedIds) {
            AccountView account = accounts.lock(accountId);
            if (account == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found");
            }
            locked.put(accountId, account);
        }
        return locked;
    }

    public TransactionView post(TransactionType type, String reference, UUID reversalOf,
                                List<LedgerEntryDraft> entries,
                                Map<UUID, AccountView> lockedAccounts, Instant now) {
        validateBalanced(entries);

        Map<UUID, Long> resultingBalances = new HashMap<>();
        for (LedgerEntryDraft entry : entries) {
            AccountView account = lockedAccounts.get(entry.accountId());
            if (account == null) {
                throw new IllegalStateException("Posting attempted without locking every account");
            }
            if (!account.currency().equals(entry.currency())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                        "Entry currency does not match its account");
            }
            long current = resultingBalances.getOrDefault(account.id(), account.balanceMinor());
            long delta = account.normalSide() == entry.direction()
                    ? entry.amountMinor()
                    : Math.negateExact(entry.amountMinor());
            long result = Math.addExact(current, delta);
            if (account.accountType() == AccountType.CUSTOMER && result < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                        "The account has insufficient funds");
            }
            resultingBalances.put(account.id(), result);
        }

        UUID transactionId = UUID.randomUUID();
        ledger.insertTransaction(transactionId, type, normalizeReference(reference), reversalOf, now);
        for (LedgerEntryDraft entry : entries) {
            ledger.insertEntry(UUID.randomUUID(), transactionId, entry, now);
        }
        resultingBalances.forEach((accountId, balance) -> accounts.updateBalance(accountId, balance, now));
        return ledger.find(transactionId).orElseThrow();
    }

    private void validateBalanced(List<LedgerEntryDraft> entries) {
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A posting requires at least two entries");
        }
        String currency = entries.getFirst().currency();
        long debits = 0;
        long credits = 0;
        for (LedgerEntryDraft entry : new ArrayList<>(entries)) {
            if (entry.amountMinor() <= 0 || !currency.equals(entry.currency())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSTING",
                        "Entries must be positive and use one currency");
            }
            if (entry.direction().name().equals("DEBIT")) {
                debits = Math.addExact(debits, entry.amountMinor());
            } else {
                credits = Math.addExact(credits, entry.amountMinor());
            }
        }
        if (debits != credits) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNBALANCED_POSTING",
                    "Total debits must equal total credits");
        }
    }

    private String normalizeReference(String reference) {
        return reference == null || reference.isBlank() ? null : reference.strip();
    }
}
