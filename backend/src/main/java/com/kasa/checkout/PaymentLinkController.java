package com.kasa.checkout;

import java.time.OffsetDateTime;
import java.util.List;
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
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
public class PaymentLinkController {

    /** A null amount lets the customer choose, as with a donation. */
    public record CreateLinkRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String description,
            @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive @Max(1_000_000) Integer maxPayments,
            OffsetDateTime expiresAt) {
    }

    public record StartSessionRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 200) String name,
            @Positive @Max(1_000_000_000_000L) Long amount) {
    }

    private final PaymentLinkService links;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public PaymentLinkController(PaymentLinkService links, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.links = links;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/payment-links")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentLinkService.LinkView create(Authentication authentication, @Valid @RequestBody CreateLinkRequest request) {
        return links.create(CurrentTenant.of(authentication), new PaymentLinkService.NewLink(request.title().trim(),
                request.description(), request.amount(), request.currency(), request.maxPayments(), request.expiresAt()));
    }

    @GetMapping("/api/v1/payment-links")
    public List<PaymentLinkService.LinkView> list(Authentication authentication) {
        return links.list(CurrentTenant.of(authentication));
    }

    @PostMapping("/api/v1/payment-links/{id}/disable")
    public PaymentLinkService.LinkView disable(Authentication authentication, @PathVariable UUID id) {
        return links.disable(CurrentTenant.of(authentication), id);
    }

    @GetMapping("/api/v1/public/links/{slug}")
    public PaymentLinkService.PublicLink get(@PathVariable String slug) {
        return links.getPublic(slug);
    }

    @PostMapping("/api/v1/public/links/{slug}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutService.CreatedSession start(@PathVariable String slug, @Valid @RequestBody StartSessionRequest request,
                                                HttpServletRequest http) {
        // Each call can create a customer record, so an open form must not become a way to fill the merchant's list.
        rateLimiter.check("link-session:" + http.getRemoteAddr(), limits.paymentLinkSessionPerIp());
        String name = request.name() == null || request.name().isBlank() ? null : request.name().trim();
        return links.startSession(slug, request.email(), name, request.amount());
    }
}
