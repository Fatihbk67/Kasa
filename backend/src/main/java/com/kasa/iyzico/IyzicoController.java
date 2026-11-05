package com.kasa.iyzico;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class IyzicoController {

    public record Started(String pageUrl) {
    }

    private final IyzicoCheckoutService service;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public IyzicoController(IyzicoCheckoutService service, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/public/checkout/{id}/iyzico")
    public Started start(@PathVariable UUID id, HttpServletRequest http) {
        rateLimiter.check("checkout-pay:" + http.getRemoteAddr(), limits.checkoutPayPerIp());
        return new Started(service.start(id, http.getRemoteAddr()));
    }

    /**
     * iyzico's page posts the customer's browser here when they finish. Whatever happened, the browser is sent on
     * to the checkout page, which shows the outcome; a hiccup in reaching iyzico is shown there too.
     */
    @PostMapping(path = "/api/v1/public/checkout/{id}/iyzico/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callback(@PathVariable UUID id, @RequestParam(required = false) String token,
                                         HttpServletRequest http) {
        rateLimiter.check("checkout-pay:" + http.getRemoteAddr(), limits.checkoutPayPerIp());
        String next = "/pay/" + id;
        if (token == null || token.isBlank() || token.length() > 200) {
            next += "?iyzico=error";
        } else {
            try {
                service.complete(id, token);
            } catch (ResponseStatusException e) {
                next += "?iyzico=error";
            }
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(next)).build();
    }
}
