package dev.aurum.account;

import dev.aurum.common.ApiException;
import dev.aurum.security.AppUserRepository;
import dev.aurum.security.AppUserView;
import dev.aurum.security.UserRole;
import dev.aurum.audit.AuditAction;
import dev.aurum.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
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
    private final AppUserRepository users;
    private final AuditService audit;
    private final String defaultCustomerUsername;
    private final Clock clock = Clock.systemUTC();

    public AccountService(AccountRepository accounts, AppUserRepository users, AuditService audit,
                          @Value("${aurum.security.users.customer.username:customer}")
                          String defaultCustomerUsername) {
        this.accounts = accounts;
        this.users = users;
        this.audit = audit;
        this.defaultCustomerUsername = defaultCustomerUsername;
    }

    @Transactional
    public AccountView create(String ownerName, String requestedCurrency) {
        return create(ownerName, defaultCustomerUsername, requestedCurrency);
    }

    @Transactional
    public AccountView create(String ownerName, String ownerUsername, String requestedCurrency) {
        String currency = requestedCurrency.toUpperCase(Locale.ROOT);
        if (accounts.findSettlement(currency).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY",
                    "Aurum Core supports INR and USD accounts");
        }
        AppUserView owner = users.findByUsername(ownerUsername.strip()).orElseThrow(() ->
                new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OWNER_NOT_FOUND",
                        "The account owner does not exist"));
        if (owner.role() != UserRole.CUSTOMER || !owner.enabled()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT_OWNER",
                    "Accounts must belong to an enabled customer user");
        }
        AccountView created = accounts.create(ownerName.strip(), owner.id(), currency, Instant.now(clock));
        audit.record(AuditAction.CREATE_ACCOUNT, "ACCOUNT", created.id(), null);
        return created;
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
        if (account.status() == status) {
            return account;
        }
        if (account.status() == AccountStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED",
                    "A closed account cannot change state");
        }
        if (status == AccountStatus.CLOSED && account.balanceMinor() != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_EMPTY",
                    "An account must have a zero balance before it can be closed");
        }
        accounts.updateStatus(accountId, status);
        AuditAction action = switch (status) {
            case ACTIVE -> AuditAction.UNFREEZE_ACCOUNT;
            case FROZEN -> AuditAction.FREEZE_ACCOUNT;
            case CLOSED -> AuditAction.CLOSE_ACCOUNT;
        };
        audit.record(action, "ACCOUNT", accountId, null);
        return accounts.find(accountId).orElseThrow();
    }
}
