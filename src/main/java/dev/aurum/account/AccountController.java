package dev.aurum.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return accounts.create(request.ownerName(), request.currency());
    }

    @GetMapping("/{accountId}")
    AccountView get(@PathVariable UUID accountId) {
        return accounts.get(accountId);
    }

    @GetMapping("/{accountId}/balance")
    BalanceResponse balance(@PathVariable UUID accountId) {
        AccountView account = accounts.get(accountId);
        return new BalanceResponse(account.id(), account.currency(), account.balanceMinor());
    }

    @PatchMapping("/{accountId}/freeze")
    AccountView freeze(@PathVariable UUID accountId) {
        return accounts.changeStatus(accountId, AccountStatus.FROZEN);
    }

    @PatchMapping("/{accountId}/unfreeze")
    AccountView unfreeze(@PathVariable UUID accountId) {
        return accounts.changeStatus(accountId, AccountStatus.ACTIVE);
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency
    ) {
    }

    public record BalanceResponse(UUID accountId, String currency, long balanceMinor) {
    }
}
