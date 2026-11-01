package com.kasa.checkout;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentStatus;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.tenant.TenantScope;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    public record CreatedSession(UUID id, OffsetDateTime expiresAt) {
    }

    /** What the customer's browser may see. The tenant id stays internal. */
    public record PublicSession(UUID id, String merchantName, long amount, String currency, String description,
                                String status, String failureCode, String paymentStatus, OffsetDateTime expiresAt) {
    }

    private record Session(PublicSession view, UUID tenantId, UUID paymentLinkId, UUID customerId) {
    }

    private record LinkLimits(Integer maxPayments, boolean closed) {
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
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return insert(tenantId, amount, currency, description, null, null);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreatedSession createForLink(UUID tenantId, long amount, String currency, String description, UUID paymentLinkId,
                                        UUID customerId) {
        return insert(tenantId, amount, currency, description, paymentLinkId, customerId);
    }

    private CreatedSession insert(UUID tenantId, long amount, String currency, String description, UUID paymentLinkId,
                                  UUID customerId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(LIFETIME);
        jdbc.sql("""
                        INSERT INTO checkout_sessions (id, tenant_id, amount, currency, description, status, expires_at,
                                                       payment_link_id, customer_id)
                        VALUES (:id, :tenantId, :amount, :currency, :description, 'OPEN', :expiresAt, :linkId, :customerId)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("amount", amount)
                .param("currency", currency)
                .param("description", description)
                .param("expiresAt", expiresAt)
                .param("linkId", paymentLinkId)
                .param("customerId", customerId)
                .update();
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
            if (session.paymentLinkId() != null) {
                claimLinkPlace(session.paymentLinkId());
            }
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
        PaymentStatus paymentStatus = null;
        try {
            OperationResult result = payments.create(tenantId, "checkout:" + sessionId + ":" + UUID.randomUUID(),
                    view.amount(), view.currency(), cardToken, view.description(), session.customerId());
            paymentId = result.payment().id();
            failureCode = result.payment().failureCode();
            paymentStatus = result.payment().status();
            if (paymentStatus != PaymentStatus.DECLINED && paymentStatus != PaymentStatus.FAILED) {
                outcome = "COMPLETED";
            }
        } finally {
            finish(tenantId, sessionId, outcome, paymentId, failureCode);
        }
        if (session.paymentLinkId() != null && paymentStatus == PaymentStatus.AUTHORIZED) {
            captureForLink(tenantId, sessionId, paymentId);
        }
        return load(sessionId).view();
    }

    /**
     * Places on a link are worth counting exactly: one left and twenty people paying must mean one charge.
     * Lock order does the work, since whoever holds this row counts the others' claims before making their own.
     */
    private void claimLinkPlace(UUID paymentLinkId) {
        LinkLimits link = jdbc.sql("""
                        SELECT max_payments, disabled_at IS NOT NULL OR expires_at < now() AS closed
                        FROM payment_links WHERE id = :id FOR UPDATE
                        """)
                .param("id", paymentLinkId)
                .query((rs, rowNum) -> new LinkLimits(rs.getObject("max_payments", Integer.class), rs.getBoolean("closed")))
                .single();
        if (link.closed() || (link.maxPayments() != null && placesTaken(paymentLinkId) >= link.maxPayments())) {
            throw new ResponseStatusException(HttpStatus.GONE, "this payment link is no longer available");
        }
    }

    /**
     * Counted from the sessions rather than kept in a counter, so a crash mid-payment cannot leak a place: a stuck
     * session stops counting when it expires, and a payment the bank later refuses stops counting at once.
     */
    public long placesTaken(UUID paymentLinkId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM checkout_sessions s
                        LEFT JOIN payments p ON p.id = s.payment_id
                        WHERE s.payment_link_id = :id
                          AND (s.status = 'PROCESSING' AND s.expires_at > now()
                               OR s.status = 'COMPLETED' AND p.status NOT IN ('DECLINED', 'FAILED'))
                        """)
                .param("id", paymentLinkId)
                .query(Long.class)
                .single();
    }

    /** A shared link has no merchant watching it, so its money is collected at once. The customer has paid either way. */
    private void captureForLink(UUID tenantId, UUID sessionId, UUID paymentId) {
        try {
            payments.capture(tenantId, paymentId, "link-capture:" + sessionId, null);
        } catch (RuntimeException e) {
            log.error("Could not capture payment {} from a link; the merchant can capture it by hand", paymentId, e);
        }
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
                            expiresAt),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("payment_link_id", UUID.class),
                            rs.getObject("customer_id", UUID.class));
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "checkout session not found"));
    }
}
