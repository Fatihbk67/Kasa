package com.kasa.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.settlement.SettlementService;

/** For the platform operator, not merchants. Access is limited in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ReconciliationService reconciliation;
    private final SettlementService settlement;

    public AdminController(ReconciliationService reconciliation, SettlementService settlement) {
        this.reconciliation = reconciliation;
        this.settlement = settlement;
    }

    @PostMapping("/reconciliation/runs")
    public ReconciliationService.RunDetail reconcile(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.reconcile(date);
    }

    @GetMapping("/reconciliation/runs")
    public List<ReconciliationService.RunView> listRuns() {
        return reconciliation.listRuns();
    }

    @GetMapping("/reconciliation/runs/{id}")
    public ReconciliationService.RunDetail getRun(@PathVariable UUID id) {
        return reconciliation.get(id);
    }

    /** Settles everything completed so far, without waiting for the daily job. */
    @PostMapping("/settlements/run")
    public Map<String, Integer> settleNow() {
        return Map.of("payoutsCreated", settlement.settleAll(Instant.now()));
    }
}
