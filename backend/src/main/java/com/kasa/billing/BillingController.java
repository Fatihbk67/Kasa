package com.kasa.billing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.billing.BillingService.PlanView;
import com.kasa.billing.BillingService.SubscriptionDetail;
import com.kasa.billing.BillingService.SubscriptionPage;
import com.kasa.billing.BillingService.Summary;
import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
public class BillingController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("INCOMPLETE", "ACTIVE", "PAST_DUE", "CANCELED");

    public record CreatePlanRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp = "MONTH|YEAR") String billingInterval) {
    }

    public record SubscribeRequest(
            @NotNull UUID customerId,
            @NotNull UUID planId,
            @NotNull @Pattern(regexp = "tok_[a-z_]{1,64}") String cardToken) {
    }

    public record ChangePlanRequest(@NotNull UUID planId) {
    }

    public record CancelRequest(boolean atPeriodEnd) {
    }

    public record UpdateCardRequest(@NotNull @Pattern(regexp = "tok_[a-z_]{1,64}") String cardToken) {
    }

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(Authentication authentication, @Valid @RequestBody CreatePlanRequest request) {
        return billing.createPlan(CurrentTenant.of(authentication), request.name().trim(), request.amount(), request.currency(),
                request.billingInterval());
    }

    @GetMapping("/plans")
    public List<PlanView> listPlans(Authentication authentication) {
        return billing.listPlans(CurrentTenant.of(authentication));
    }

    @PostMapping("/plans/{id}/archive")
    public PlanView archivePlan(Authentication authentication, @PathVariable UUID id) {
        return billing.archivePlan(CurrentTenant.of(authentication), id);
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionDetail subscribe(Authentication authentication, @Valid @RequestBody SubscribeRequest request) {
        return billing.subscribe(CurrentTenant.of(authentication), request.customerId(), request.planId(), request.cardToken());
    }

    @GetMapping("/subscriptions")
    public SubscriptionPage list(Authentication authentication,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) UUID customerId,
                                 @RequestParam(required = false) UUID before,
                                 @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
        }
        return billing.list(CurrentTenant.of(authentication), status, customerId, before, limit);
    }

    @GetMapping("/subscriptions/{id}")
    public SubscriptionDetail get(Authentication authentication, @PathVariable UUID id) {
        return billing.get(CurrentTenant.of(authentication), id);
    }

    @PostMapping("/subscriptions/{id}/change-plan")
    public SubscriptionDetail changePlan(Authentication authentication, @PathVariable UUID id,
                                         @Valid @RequestBody ChangePlanRequest request) {
        return billing.changePlan(CurrentTenant.of(authentication), id, request.planId());
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public SubscriptionDetail cancel(Authentication authentication, @PathVariable UUID id, @RequestBody CancelRequest request) {
        return billing.cancel(CurrentTenant.of(authentication), id, request.atPeriodEnd());
    }

    @PostMapping("/subscriptions/{id}/resume")
    public SubscriptionDetail resume(Authentication authentication, @PathVariable UUID id) {
        return billing.resume(CurrentTenant.of(authentication), id);
    }

    @PutMapping("/subscriptions/{id}/card")
    public SubscriptionDetail updateCard(Authentication authentication, @PathVariable UUID id,
                                         @Valid @RequestBody UpdateCardRequest request) {
        return billing.updateCard(CurrentTenant.of(authentication), id, request.cardToken());
    }

    @PostMapping("/subscriptions/{id}/simulate-renewal")
    public SubscriptionDetail simulateRenewal(Authentication authentication, @PathVariable UUID id) {
        return billing.simulateRenewal(CurrentTenant.of(authentication), id);
    }

    @GetMapping("/billing/summary")
    public Summary summary(Authentication authentication) {
        return billing.summary(CurrentTenant.of(authentication));
    }
}
