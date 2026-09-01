package dev.aurum.account;

import dev.aurum.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock = Clock.systemUTC();

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountView create(String ownerName, String requestedCurrency) {
        String currency = requestedCurrency.toUpperCase(Locale.ROOT);
        if (accounts.findSettlement(currency).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY",
                    "Aurum Core supports INR and USD accounts");
        }
        return accounts.create(ownerName.strip(), currency, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public AccountView get(UUID accountId) {
        return accounts.find(accountId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found"));
    }

    @Transactional
    public AccountView changeStatus(UUID accountId, AccountStatus status) {
        AccountView account = accounts.lock(accountId);
        if (account == null || account.accountType() != AccountType.CUSTOMER) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found");
        }
        accounts.updateStatus(accountId, status);
        return accounts.find(accountId).orElseThrow();
    }
}

