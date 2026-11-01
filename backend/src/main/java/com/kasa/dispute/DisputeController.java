package com.kasa.dispute;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.dispute.DisputeService.DisputeFilter;
import com.kasa.dispute.DisputeService.DisputePage;
import com.kasa.dispute.DisputeService.DisputeView;
import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/disputes")
public class DisputeController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final java.util.Set<String> STATUSES = java.util.Set.of("NEEDS_RESPONSE", "UNDER_REVIEW", "WON", "LOST");

    /** Banks open disputes in real life. This lets anyone trying the demo cause one, since the whole bank is simulated. */
    public record SimulateRequest(
            @NotNull UUID paymentId,
            @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = "FRAUDULENT|PRODUCT_NOT_RECEIVED|DUPLICATE|PRODUCT_UNACCEPTABLE|OTHER") String reason) {
    }

    public record EvidenceRequest(
            @NotBlank @Size(max = 4000) String explanation,
            @Size(max = 200) String tracking,
            @Size(max = 4000) String communication) {
    }

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeView simulate(Authentication authentication, @Valid @RequestBody SimulateRequest request) {
        return disputes.open(CurrentTenant.of(authentication), request.paymentId(), request.amount(), request.reason());
    }

    @GetMapping
    public DisputePage list(Authentication authentication,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) UUID paymentId,
                            @RequestParam(required = false) UUID before,
                            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
        }
        return disputes.list(CurrentTenant.of(authentication), new DisputeFilter(status, paymentId), before, limit);
    }

    @GetMapping("/{id}")
    public DisputeView get(Authentication authentication, @PathVariable UUID id) {
        return disputes.get(CurrentTenant.of(authentication), id);
    }

    @PostMapping("/{id}/evidence")
    public DisputeView submitEvidence(Authentication authentication, @PathVariable UUID id,
                                      @Valid @RequestBody EvidenceRequest request) {
        return disputes.submitEvidence(CurrentTenant.of(authentication), id, new DisputeService.Evidence(
                request.explanation().trim(), blankToNull(request.tracking()), blankToNull(request.communication())));
    }

    @PostMapping("/{id}/accept")
    public DisputeView accept(Authentication authentication, @PathVariable UUID id) {
        return disputes.accept(CurrentTenant.of(authentication), id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
