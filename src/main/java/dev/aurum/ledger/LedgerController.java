package dev.aurum.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import dev.aurum.security.ResourceAuthorizationService;
import dev.aurum.reliability.FailureProbe;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    private static final long MAX_AMOUNT_MINOR = 9_000_000_000_000_000L;
    private final LedgerService ledger;
    private final ResourceAuthorizationService authorization;
    private final FailureProbe failures;

    public LedgerController(LedgerService ledger, ResourceAuthorizationService authorization,
                            FailureProbe failures) {
        this.ledger = ledger;
        this.authorization = authorization;
        this.failures = failures;
    }

    @PostMapping("/accounts/{accountId}/fund")
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView fund(@PathVariable UUID accountId,
                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                         @Valid @RequestBody FundingRequest request) {
        TransactionView result = ledger.fund(accountId, request.amountMinor(), request.currency(),
                request.reference(), idempotencyKey);
        return afterCommit(result);
    }

    @PostMapping("/accounts/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView withdraw(@PathVariable UUID accountId,
                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody WithdrawalRequest request) {
        authorization.requireAccountAccess(accountId);
        TransactionView result = ledger.withdraw(accountId, request.amountMinor(), request.currency(),
                request.reference(), idempotencyKey);
        return afterCommit(result);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody TransferRequest request) {
        authorization.requireAccountAccess(request.sourceAccountId());
        TransactionView result = ledger.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amountMinor(), request.currency(), request.reference(), idempotencyKey);
        return afterCommit(result);
    }

    @PostMapping("/transactions/{transactionId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView reverse(@PathVariable UUID transactionId,
                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @Valid @RequestBody ReversalRequest request) {
        return afterCommit(ledger.reverse(transactionId, request.reason(), idempotencyKey));
    }

    @GetMapping("/transactions/{transactionId}")
    TransactionView transaction(@PathVariable UUID transactionId) {
        authorization.requireTransactionAccess(transactionId);
        return ledger.get(transactionId);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    List<TransactionSummary> history(@PathVariable UUID accountId,
                                     @RequestParam(required = false) UUID before,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        authorization.requireAccountAccess(accountId);
        return ledger.history(accountId, before, limit);
    }

    public record FundingRequest(
            @Min(1) @Max(MAX_AMOUNT_MINOR) long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 200) String reference
    ) {
    }

    public record WithdrawalRequest(
            @Min(1) @Max(MAX_AMOUNT_MINOR) long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 200) String reference
    ) {
    }

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Min(1) @Max(MAX_AMOUNT_MINOR) long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 200) String reference
    ) {
    }

    public record ReversalRequest(@NotBlank @Size(max = 200) String reason) {
    }

    private TransactionView afterCommit(TransactionView result) {
        failures.check(FailureProbe.FailurePoint.AFTER_COMMIT_BEFORE_RESPONSE);
        return result;
    }
}
