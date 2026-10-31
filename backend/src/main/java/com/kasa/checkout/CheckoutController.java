package com.kasa.checkout;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;
import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
public class CheckoutController {

    public record CreateSessionRequest(
            @NotNull @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {
    }

    public record PayRequest(@NotNull @Pattern(regexp = "tok_[a-z_]{1,64}") String cardToken) {
    }

    private final CheckoutService checkout;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public CheckoutController(CheckoutService checkout, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.checkout = checkout;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/checkout-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutService.CreatedSession create(Authentication authentication,
                                                 @Valid @RequestBody CreateSessionRequest request) {
        return checkout.create(CurrentTenant.of(authentication), request.amount(), request.currency(),
                request.description());
    }

    @GetMapping("/api/v1/public/checkout/{id}")
    public CheckoutService.PublicSession get(@PathVariable UUID id) {
        return checkout.get(id);
    }

    @PostMapping("/api/v1/public/checkout/{id}/pay")
    public CheckoutService.PublicSession pay(@PathVariable UUID id, @Valid @RequestBody PayRequest request,
                                             HttpServletRequest http) {
        rateLimiter.check("checkout-pay:" + http.getRemoteAddr(), limits.checkoutPayPerIp());
        return checkout.pay(id, request.cardToken());
    }
}
