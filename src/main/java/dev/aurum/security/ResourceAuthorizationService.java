package dev.aurum.security;

import dev.aurum.account.AccountRepository;
import dev.aurum.common.ApiException;
import dev.aurum.ledger.LedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ResourceAuthorizationService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public ResourceAuthorizationService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public void requireAccountAccess(UUID accountId) {
        String customer = customerUsername();
        if (customer != null && !accounts.isOwnedBy(accountId, customer)) {
            notFound("Account");
        }
    }

    public void requireTransactionAccess(UUID transactionId) {
        String customer = customerUsername();
        if (customer != null && !ledger.isVisibleToOwner(transactionId, customer)) {
            notFound("Transaction");
        }
    }

    private String customerUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        boolean privileged = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR")
                        || authority.getAuthority().equals("ROLE_AUDITOR")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        if (privileged) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_CUSTOMER"))
                ? authentication.getName() : null;
    }

    private void notFound(String resource) {
        throw new ApiException(HttpStatus.NOT_FOUND,
                resource.toUpperCase() + "_NOT_FOUND", resource + " was not found");
    }
}
