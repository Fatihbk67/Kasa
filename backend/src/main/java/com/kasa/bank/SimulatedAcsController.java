package com.kasa.bank;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** The simulated card bank's verification page. In real life this lives on the bank's own domain. */
@RestController
public class SimulatedAcsController {

    public record AnswerRequest(@Pattern(regexp = "\\d{6}") String code, Boolean cancel) {
    }

    private final SimulatedAcs acs;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public SimulatedAcsController(SimulatedAcs acs, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.acs = acs;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @GetMapping("/api/v1/public/bank/3ds/{id}")
    public SimulatedAcs.Challenge challenge(@PathVariable UUID id) {
        return acs.challenge(id);
    }

    @PostMapping("/api/v1/public/bank/3ds/{id}")
    public SimulatedAcs.Answer answer(@PathVariable UUID id, @Valid @RequestBody AnswerRequest request, HttpServletRequest http) {
        rateLimiter.check("checkout-pay:" + http.getRemoteAddr(), limits.checkoutPayPerIp());
        return acs.answer(id, request.code(), Boolean.TRUE.equals(request.cancel()));
    }
}
