package dev.aurum.reconciliation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Validated
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReconciliationRunService runs;

    public ReconciliationController(ReconciliationService reconciliation,
                                    ReconciliationRunService runs) {
        this.reconciliation = reconciliation;
        this.runs = runs;
    }

    @GetMapping
    ReconciliationService.ReconciliationResult reconcile() {
        return reconciliation.reconcile();
    }

    @PostMapping("/rebuild")
    ReconciliationService.RebuildResult rebuild() {
        return reconciliation.rebuild();
    }

    @GetMapping("/runs")
    List<ReconciliationRunView> recentRuns(
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return runs.recent(limit);
    }
}
