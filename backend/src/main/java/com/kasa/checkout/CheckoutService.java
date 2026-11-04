package com.kasa.checkout;

import java.time.Duration;
import java.time.Instant;
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

import com.kasa.bank.SimulatedAcs;
import com.kasa.bank.ThreeDsSigner;
import com.kasa.payment.CardAuthentication;
import com.kasa.payment.Installments;
import com.kasa.payment.PaymentService;
import com.kasa.risk.RiskEngine;
import com.kasa.security.SecretCipher;
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
                                String status, String failureCode, String paymentStatus, OffsetDateTime expiresAt,
                                String challengeUrl, int installments) {
    }

    private record Session(PublicSession view, UUID tenantId, UUID paymentLinkId, UUID customerId) {
    }

    private record LinkLimits(Integer maxPayments, boolean closed) {
    }

    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final String EXPIRED = "EXPIRED";
    private static final String THREE_DS_FAILED = "3ds_failed";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PaymentService payments;
    private final SimulatedAcs acs;
    private final ThreeDsSigner signer;
    private final SecretCipher cipher;

    public CheckoutService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PaymentService payments,
                           SimulatedAcs acs, ThreeDsSigner signer, SecretCipher cipher) {
        this.acs = acs;
        this.signer = signer;
        this.cipher = cipher;
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

    public PublicSession pay(UUID sessionId, String cardToken) {
        return pay(sessionId, cardToken, 1);
    }

    /**
     * Takes the session from OPEN to PROCESSING first, so a double click or a second tab cannot charge twice.
     * The card's bank is asked next whether it wants to verify its holder. If it does, the customer goes to the
     * bank's page and the card is charged only when they return with its signed approval. A declined card or a
     * failed verification returns the session to OPEN, which lets the customer try another card.
     */
    public PublicSession pay(UUID sessionId, String cardToken, int installments) {
        Session session = load(sessionId);
        PublicSession view = session.view();
        if (view.status().equals("COMPLETED")) {
            return view;
        }
        if (!Installments.isAllowed(installments, view.amount())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "instalments are not available for this payment");
        }
        UUID tenantId = session.tenantId();
        Integer claimed = tx.execute(status -> {
            tenantScope.apply(tenantId);
            if (session.paymentLinkId() != null) {
                claimLinkPlace(session.paymentLinkId());
            }
            // A customer who left for their bank and never came back may start again once that visit has expired.
            return jdbc.sql("""
                            UPDATE checkout_sessions
                            SET status = 'PROCESSING', failure_code = NULL, installments = :installments,
                                three_ds_id = NULL, three_ds_expires_at = NULL, updated_at = now()
                            WHERE id = :id AND expires_at > now()
                              AND (status = 'OPEN' OR (status = 'AUTHENTICATING' AND three_ds_expires_at <= now()))
                            """)
                    .param("id", sessionId)
                    .param("installments", installments)
                    .update();
        });
        if (claimed == 0) {
            if (view.status().equals(EXPIRED)) {
                throw new ResponseStatusException(HttpStatus.GONE, "checkout session has expired");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is already in progress");
        }

        UUID authenticationId = UUID.randomUUID();
        SimulatedAcs.Started started;
        try {
            started = acs.start(authenticationId, cardToken, view.merchantName(), view.amount(), view.currency(),
                    "/api/v1/public/checkout/" + sessionId + "/3ds-callback");
            if (started.challenge()) {
                awaitChallenge(tenantId, sessionId, authenticationId, cardToken);
                return load(sessionId).view();
            }
        } catch (RuntimeException e) {
            finish(tenantId, sessionId, "OPEN", null, null);
            throw e;
        }
        if (!signer.verify(started.frictionless()) || !"Y".equals(started.frictionless().status())) {
            finish(tenantId, sessionId, "OPEN", null, THREE_DS_FAILED);
            return load(sessionId).view();
        }
        return charge(session, cardToken, "checkout:" + sessionId + ":" + authenticationId,
                new CardAuthentication(CardAuthentication.Method.FRICTIONLESS, started.frictionless().eci()), installments);
    }

    private void awaitChallenge(UUID tenantId, UUID sessionId, UUID authenticationId, String cardToken) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("""
                            INSERT INTO three_ds_authentications (id, tenant_id, session_id, card_token, status)
                            VALUES (:id, :tenantId, :sessionId, :token, 'CHALLENGED')
                            """)
                    .param("id", authenticationId)
                    .param("tenantId", tenantId)
                    .param("sessionId", sessionId)
                    .param("token", cipher.encrypt(tokenContext(authenticationId), cardToken))
                    .update();
            jdbc.sql("""
                            UPDATE checkout_sessions
                            SET status = 'AUTHENTICATING', three_ds_id = :authId,
                                three_ds_expires_at = now() + make_interval(secs => :seconds), updated_at = now()
                            WHERE id = :id
                            """)
                    .param("authId", authenticationId)
                    .param("seconds", SimulatedAcs.CHALLENGE_LIFETIME.toSeconds())
                    .param("id", sessionId)
                    .update();
        });
    }

    /**
     * The bank's verdict, brought back by the customer's browser. Three things must hold before any money moves:
     * the bank really signed it, it belongs to this session's open verification, and it has not been used before.
     * The last two are a single conditional update, so a verdict replayed in two tabs at once charges only once.
     */
    public PublicSession completeThreeDs(UUID sessionId, ThreeDsSigner.SignedResult result) {
        if (!signer.verify(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the bank's signature does not match");
        }
        long age = Instant.now().getEpochSecond() - result.issuedAt();
        if (age < -60 || age > SimulatedAcs.CHALLENGE_LIFETIME.toSeconds()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the bank's answer is too old");
        }
        record Owner(UUID tenantId, UUID sessionId) {
        }
        Owner owner = jdbc.sql("SELECT * FROM find_three_ds_authentication(:id)")
                .param("id", result.authenticationId())
                .query((rs, rowNum) -> new Owner(rs.getObject("tenant_id", UUID.class), rs.getObject("session_id", UUID.class)))
                .optional()
                .filter(found -> found.sessionId().equals(sessionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "verification not found"));
        boolean passed = "Y".equals(result.status());
        String cardToken = tx.execute(status -> {
            tenantScope.apply(owner.tenantId());
            int claimed = jdbc.sql("""
                            UPDATE checkout_sessions SET status = 'PROCESSING', three_ds_id = NULL, three_ds_expires_at = NULL,
                                                         updated_at = now()
                            WHERE id = :id AND status = 'AUTHENTICATING' AND three_ds_id = :authId
                              AND three_ds_expires_at > now() AND expires_at > now()
                            """)
                    .param("id", sessionId)
                    .param("authId", result.authenticationId())
                    .update();
            if (claimed == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this verification was already used or has expired");
            }
            String stored = jdbc.sql("""
                            UPDATE three_ds_authentications SET status = :status, eci = :eci, completed_at = now()
                            WHERE id = :id RETURNING card_token
                            """)
                    .param("status", passed ? "AUTHENTICATED" : "FAILED")
                    .param("eci", result.eci())
                    .param("id", result.authenticationId())
                    .query(String.class)
                    .single();
            return cipher.decrypt(tokenContext(result.authenticationId()), stored);
        });
        Session session = load(sessionId);
        if (!passed) {
            finish(owner.tenantId(), sessionId, "OPEN", null, THREE_DS_FAILED);
            return load(sessionId).view();
        }
        return charge(session, cardToken, "checkout:" + sessionId + ":" + result.authenticationId(),
                new CardAuthentication(CardAuthentication.Method.CHALLENGE, result.eci()), session.view().installments());
    }

    private PublicSession charge(Session session, String cardToken, String key, CardAuthentication authentication, int installments) {
        PublicSession view = session.view();
        UUID sessionId = view.id();
        UUID tenantId = session.tenantId();
        String outcome = "OPEN";
        UUID paymentId = null;
        String failureCode = null;
        PaymentStatus paymentStatus = null;
        boolean held = false;
        try {
            OperationResult result = payments.create(tenantId, key, view.amount(), view.currency(), cardToken,
                    view.description(), session.customerId(), RiskEngine.Screening.FULL, authentication, installments);
            paymentId = result.payment().id();
            failureCode = result.payment().failureCode();
            paymentStatus = result.payment().status();
            held = "OPEN".equals(result.payment().reviewStatus());
            if (paymentStatus != PaymentStatus.DECLINED && paymentStatus != PaymentStatus.FAILED) {
                outcome = "COMPLETED";
            }
        } finally {
            finish(tenantId, sessionId, outcome, paymentId, failureCode);
        }
        // A held payment is collected only after a person releases it. The customer still sees success: their card
        // was authorized, and whether to keep the money is now the business's decision.
        if (session.paymentLinkId() != null && paymentStatus == PaymentStatus.AUTHORIZED && !held) {
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
                               OR s.status = 'AUTHENTICATING' AND s.expires_at > now() AND s.three_ds_expires_at > now()
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

    private static String tokenContext(UUID authenticationId) {
        return "three-ds-card:" + authenticationId;
    }

    private Session load(UUID sessionId) {
        return jdbc.sql("SELECT * FROM find_checkout_session(:id)")
                .param("id", sessionId)
                .query((rs, rowNum) -> {
                    OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    String stored = rs.getString("status");
                    boolean expired = !stored.equals("COMPLETED") && expiresAt.isBefore(OffsetDateTime.now());
                    OffsetDateTime challengeEnds = rs.getObject("three_ds_expires_at", OffsetDateTime.class);
                    boolean challengeOpen = stored.equals("AUTHENTICATING") && challengeEnds != null
                            && challengeEnds.isAfter(OffsetDateTime.now());
                    // An abandoned visit to the bank reads as open again, matching what pay() will accept.
                    String status = stored.equals("AUTHENTICATING") && !challengeOpen ? "OPEN" : stored;
                    UUID challengeId = rs.getObject("three_ds_id", UUID.class);
                    return new Session(new PublicSession(
                            rs.getObject("id", UUID.class),
                            rs.getString("merchant_name"),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            rs.getString("description"),
                            expired ? EXPIRED : status,
                            rs.getString("failure_code"),
                            rs.getString("payment_status"),
                            expiresAt,
                            challengeOpen && !expired ? "/bank/3ds/" + challengeId : null,
                            rs.getInt("installments")),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("payment_link_id", UUID.class),
                            rs.getObject("customer_id", UUID.class));
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "checkout session not found"));
    }
}
