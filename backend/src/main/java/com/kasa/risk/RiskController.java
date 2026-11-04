package com.kasa.risk;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.payment.PaymentViews.PaymentView;
import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * For owners and administrators signed in to the dashboard. An API key cannot loosen the rules that guard it,
 * nor release a payment they held: whoever steals a key should not be able to switch the alarm off.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    public record RuleRequest(@NotBlank String kind, @NotNull Long threshold, Integer windowMinutes, String currency,
                              @NotBlank String action, Boolean enabled) {
    }

    public record BlockRequest(@NotNull UUID paymentId, @NotBlank @Size(max = 300) String reason) {
    }

    public record ApproveRequest(Boolean capture) {
    }

    public record RejectRequest(Boolean blockCard) {
    }

    private final RiskService risk;

    public RiskController(RiskService risk) {
        this.risk = risk;
    }

    @GetMapping("/rules")
    public List<RiskService.RuleView> rules(Authentication authentication) {
        return risk.rules(CurrentTenant.of(authentication));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RiskService.RuleView add(Authentication authentication, @Valid @RequestBody RuleRequest request) {
        return risk.addRule(CurrentTenant.of(authentication), input(request));
    }

    @PostMapping("/rules/recommended")
    @ResponseStatus(HttpStatus.CREATED)
    public List<RiskService.RuleView> recommended(Authentication authentication) {
        return risk.addRecommended(CurrentTenant.of(authentication));
    }

    @PutMapping("/rules/{id}")
    public RiskService.RuleView update(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return risk.updateRule(CurrentTenant.of(authentication), id, input(request));
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable UUID id) {
        risk.deleteRule(CurrentTenant.of(authentication), id);
    }

    @GetMapping("/blocklist")
    public List<RiskService.BlockedCard> blocklist(Authentication authentication) {
        return risk.blocklist(CurrentTenant.of(authentication));
    }

    @PostMapping("/blocklist")
    @ResponseStatus(HttpStatus.CREATED)
    public RiskService.BlockedCard block(Authentication authentication, @Valid @RequestBody BlockRequest request) {
        return risk.blockCardOf(CurrentTenant.of(authentication), request.paymentId(), request.reason().trim());
    }

    @DeleteMapping("/blocklist/{fingerprint}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(Authentication authentication, @PathVariable String fingerprint) {
        risk.unblock(CurrentTenant.of(authentication), fingerprint);
    }

    @GetMapping("/reviews")
    public List<PaymentView> reviews(Authentication authentication) {
        return risk.reviewQueue(CurrentTenant.of(authentication));
    }

    @PostMapping("/reviews/{paymentId}/approve")
    public PaymentView approve(Authentication authentication, @PathVariable UUID paymentId,
                               @RequestBody(required = false) ApproveRequest request) {
        boolean capture = request != null && Boolean.TRUE.equals(request.capture());
        return risk.decide(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), paymentId, true, capture, false);
    }

    @PostMapping("/reviews/{paymentId}/reject")
    public PaymentView reject(Authentication authentication, @PathVariable UUID paymentId,
                              @RequestBody(required = false) RejectRequest request) {
        boolean blockCard = request != null && Boolean.TRUE.equals(request.blockCard());
        return risk.decide(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), paymentId, false, false, blockCard);
    }

    private static RiskService.RuleInput input(RuleRequest request) {
        return new RiskService.RuleInput(request.kind(), request.threshold(), request.windowMinutes(), request.currency(),
                request.action(), request.enabled());
    }
}
