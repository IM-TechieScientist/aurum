package dev.aurum.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    List<AuditEventView> recent(
            @RequestParam(required = false) @Min(1) Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return audit.recent(before, limit);
    }
}
