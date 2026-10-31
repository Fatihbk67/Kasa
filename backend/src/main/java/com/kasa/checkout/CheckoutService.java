package com.kasa.checkout;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentStatus;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.tenant.TenantScope;

@Service
public class CheckoutService {

    public record CreatedSession(UUID id, OffsetDateTime expiresAt) {
    }

    /** What the customer's browser may see. The tenant id stays internal. */
    public record PublicSession(UUID id, String merchantName, long amount, String currency, String description,
                                String status, String failureCode, String paymentStatus, OffsetDateTime expiresAt) {
    }

    private record Session(PublicSession view, UUID tenantId) {
    }

    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final String EXPIRED = "EXPIRED";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PaymentService payments;

    public CheckoutService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PaymentService payments) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.payments = payments;
    }

    public CreatedSession create(UUID tenantId, long amount, String currency, String description) {
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(LIFETIME);
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("""
                            INSERT INTO checkout_sessions (id, tenant_id, amount, currency, description, status, expires_at)
                            VALUES (:id, :tenantId, :amount, :currency, :description, 'OPEN', :expiresAt)
                            """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("amount", amount)
                    .param("currency", currency)
                    .param("description", description)
                    .param("expiresAt", expiresAt)
                    .update();
        });
        return new CreatedSession(id, expiresAt);
    }

    public PublicSession get(UUID sessionId) {
        return load(sessionId).view();
    }

    /**
     * Takes the session from OPEN to PROCESSING first, so a double click or a second tab cannot charge twice.
     * A declined card returns it to OPEN, which lets the customer try another card.
     */
    public PublicSession pay(UUID sessionId, String cardToken) {
        Session session = load(sessionId);
        PublicSession view = session.view();
        if (view.status().equals("COMPLETED")) {
            return view;
        }
        UUID tenantId = session.tenantId();
        Integer claimed = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("""
                            UPDATE checkout_sessions
                            SET status = 'PROCESSING', failure_code = NULL, updated_at = now()
                            WHERE id = :id AND status = 'OPEN' AND expires_at > now()
                            """)
                    .param("id", sessionId)
                    .update();
        });
        if (claimed == 0) {
            if (view.status().equals(EXPIRED)) {
                throw new ResponseStatusException(HttpStatus.GONE, "checkout session has expired");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is already in progress");
        }

        String outcome = "OPEN";
        UUID paymentId = null;
        String failureCode = null;
        try {
            OperationResult result = payments.create(tenantId, "checkout:" + sessionId + ":" + UUID.randomUUID(),
                    view.amount(), view.currency(), cardToken, view.description());
            paymentId = result.payment().id();
            failureCode = result.payment().failureCode();
            PaymentStatus paymentStatus = result.payment().status();
            if (paymentStatus != PaymentStatus.DECLINED && paymentStatus != PaymentStatus.FAILED) {
                outcome = "COMPLETED";
            }
        } finally {
            finish(tenantId, sessionId, outcome, paymentId, failureCode);
        }
        return load(sessionId).view();
    }

    private void finish(UUID tenantId, UUID sessionId, String status, UUID paymentId, String failureCode) {
        tx.executeWithoutResult(transaction -> {
            tenantScope.apply(tenantId);
            jdbc.sql("""
                            UPDATE checkout_sessions
                            SET status = :status, payment_id = :paymentId, failure_code = :failureCode, updated_at = now()
                            WHERE id = :id
                            """)
                    .param("status", status)
                    .param("paymentId", paymentId)
                    .param("failureCode", failureCode)
                    .param("id", sessionId)
                    .update();
        });
    }

    private Session load(UUID sessionId) {
        return jdbc.sql("SELECT * FROM find_checkout_session(:id)")
                .param("id", sessionId)
                .query((rs, rowNum) -> {
                    OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    String status = rs.getString("status");
                    boolean expired = !status.equals("COMPLETED") && expiresAt.isBefore(OffsetDateTime.now());
                    return new Session(new PublicSession(
                            rs.getObject("id", UUID.class),
                            rs.getString("merchant_name"),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            rs.getString("description"),
                            expired ? EXPIRED : status,
                            rs.getString("failure_code"),
                            rs.getString("payment_status"),
                            expiresAt), rs.getObject("tenant_id", UUID.class));
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "checkout session not found"));
    }
}
