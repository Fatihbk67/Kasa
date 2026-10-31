package com.kasa.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.payment.PaymentViews.PaymentDetail;
import com.kasa.payment.PaymentViews.PaymentPage;
import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_PAGE_SIZE = 100;

    /** Accepts only a token, so a raw card number can never enter the system. */
    public record CreatePaymentRequest(
            @NotNull @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp = "tok_[a-z_]{1,64}") String cardToken,
            @Size(max = 500) String description) {
    }

    public record AmountRequest(@Positive @Max(1_000_000_000_000L) Long amount) {
    }

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<OperationResult> create(Authentication authentication,
                                                  @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                  @Valid @RequestBody CreatePaymentRequest request) {
        OperationResult result = payments.create(CurrentTenant.of(authentication), key, request.amount(),
                request.currency(), request.cardToken(), request.description());
        return respond(result, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public PaymentDetail get(Authentication authentication, @PathVariable UUID id) {
        return payments.get(CurrentTenant.of(authentication), id);
    }

    @GetMapping
    public PaymentPage list(Authentication authentication,
                            @RequestParam(required = false) PaymentStatus status,
                            @RequestParam(required = false) UUID before,
                            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return payments.list(CurrentTenant.of(authentication), status, before, limit);
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<OperationResult> capture(Authentication authentication, @PathVariable UUID id,
                                                   @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                   @Valid @RequestBody(required = false) AmountRequest request) {
        return respond(payments.capture(CurrentTenant.of(authentication), id, key, amountOf(request)), HttpStatus.OK);
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<OperationResult> voidPayment(Authentication authentication, @PathVariable UUID id,
                                                       @RequestHeader(IDEMPOTENCY_KEY) String key) {
        return respond(payments.voidPayment(CurrentTenant.of(authentication), id, key), HttpStatus.OK);
    }

    @PostMapping("/{id}/refunds")
    public ResponseEntity<OperationResult> refund(Authentication authentication, @PathVariable UUID id,
                                                  @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                  @Valid @RequestBody(required = false) AmountRequest request) {
        return respond(payments.refund(CurrentTenant.of(authentication), id, key, amountOf(request)), HttpStatus.OK);
    }

    private static Long amountOf(AmountRequest request) {
        return request == null ? null : request.amount();
    }

    // 202 tells the client the outcome is not known yet and it should poll the payment.
    private static ResponseEntity<OperationResult> respond(OperationResult result, HttpStatus whenSettled) {
        HttpStatus status = result.operation().status() == OperationStatus.PENDING ? HttpStatus.ACCEPTED : whenSettled;
        return ResponseEntity.status(status).body(result);
    }
}
