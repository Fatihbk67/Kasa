package com.kasa.iyzico;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.bank.BankTimeoutException;
import com.kasa.checkout.CheckoutService;
import com.kasa.checkout.CheckoutService.ExternalVisit;
import com.kasa.checkout.CheckoutService.PublicSession;
import com.kasa.payment.Installments;
import com.kasa.tenant.TenantScope;

/**
 * Pays a checkout session on iyzico's hosted page. The customer types their card there, so its details never
 * reach us; iyzico sends them back to our callback with a token, and only the result we fetch for that token,
 * signed by iyzico and matching this exact sale, records a payment.
 */
@Service
public class IyzicoCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(IyzicoCheckoutService.class);

    static final String DECLINED = "iyzico_declined";
    private static final int[] INSTALLMENT_CHOICES = {1, 2, 3, 6, 9, 12};
    /** iyzico's own sample address. It rejects reserved test domains such as ".test" as badly formatted. */
    static final String SANDBOX_BUYER_EMAIL = "email@email.com";

    private record Visit(UUID id, UUID tenantId, UUID sessionId, String status) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final CheckoutService checkout;
    private final IyzicoClient client;
    private final IyzicoGateway gateway;
    private final String publicUrl;

    public IyzicoCheckoutService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, CheckoutService checkout,
                                 IyzicoClient client, IyzicoGateway gateway, @Value("${kasa.public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.checkout = checkout;
        this.client = client;
        this.gateway = gateway;
        this.publicUrl = publicUrl;
    }

    /** Opens iyzico's payment page for the session and returns its address. */
    public String start(UUID sessionId, String customerIp) {
        ExternalVisit visit = checkout.beginExternal(sessionId);
        PublicSession view = visit.view();
        List<Integer> installments = IntStream.of(INSTALLMENT_CHOICES)
                .filter(count -> Installments.isAllowed(count, view.amount()))
                .boxed()
                .toList();
        IyzicoClient.Started started;
        try {
            started = client.initialize(new IyzicoClient.Sale(visit.visitId().toString(), IyzicoGateway.minor(view.amount()),
                    view.currency(), publicUrl + "/api/v1/public/checkout/" + sessionId + "/iyzico/callback", installments,
                    view.description() == null || view.description().isBlank() ? view.merchantName() + " ödemesi" : view.description(),
                    SANDBOX_BUYER_EMAIL, customerIp));
        } catch (RuntimeException e) {
            checkout.reopenExternal(sessionId, visit.visitId(), null);
            log.warn("Could not open iyzico's payment page for session {}: {}", sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "iyzico's payment page could not be opened");
        }
        tx.executeWithoutResult(status -> {
            tenantScope.apply(visit.tenantId());
            jdbc.sql("""
                            INSERT INTO iyzico_checkouts (id, tenant_id, session_id, token, page_url)
                            VALUES (:id, :tenantId, :sessionId, :token, :pageUrl)
                            """)
                    .param("id", visit.visitId())
                    .param("tenantId", visit.tenantId())
                    .param("sessionId", sessionId)
                    .param("token", started.token())
                    .param("pageUrl", started.pageUrl())
                    .update();
        });
        return started.pageUrl();
    }

    /**
     * The customer is back from iyzico. The token only names the visit: what happened is asked of iyzico itself,
     * and the answer must carry iyzico's signature and describe this sale. A visit is settled once, so a reload or
     * a replayed callback finds it finished and changes nothing.
     */
    public void complete(UUID sessionId, String token) {
        Visit visit = jdbc.sql("SELECT * FROM find_iyzico_checkout_by_token(:token)")
                .param("token", token)
                .query((rs, rowNum) -> new Visit(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getString("status")))
                .optional()
                .filter(found -> found.sessionId().equals(sessionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "iyzico payment not found"));
        if (!visit.status().equals("STARTED")) {
            return;
        }
        PublicSession view = checkout.get(sessionId);
        IyzicoClient.Result result;
        try {
            result = client.retrieve(token, visit.id().toString());
        } catch (BankTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "iyzico did not answer; please try again");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "iyzico's answer could not be verified");
        }
        if (!result.success()) {
            if (settle(visit, "FAILED", null, result.errorMessage())) {
                checkout.reopenExternal(sessionId, visit.id(), DECLINED);
            }
            return;
        }
        String mismatch = mismatch(result, visit, view);
        if (mismatch != null) {
            // Signed by iyzico yet not this sale: refuse it and give the money back rather than guess.
            log.error("iyzico payment {} does not match session {}: {}", result.paymentId(), sessionId, mismatch);
            if (settle(visit, "CANCELLED", result.paymentId(), mismatch)) {
                giveBack(result.paymentId(), visit.id());
                checkout.reopenExternal(sessionId, visit.id(), DECLINED);
            }
            return;
        }
        if (!settle(visit, "PAID", result.paymentId(), null)) {
            return;
        }
        String cardLabel = cardLabel(result);
        gateway.recordCharge(result.paymentId(), view.amount(), view.currency(), result.installment(), cardLabel);
        String cardKey = result.binNumber() == null || result.lastFourDigits() == null
                ? "card" : result.binNumber() + result.lastFourDigits();
        int installments = Installments.isAllowed(result.installment(), view.amount()) ? result.installment() : 1;
        Optional<PublicSession> paid;
        try {
            paid = checkout.completeExternal(sessionId, visit.id(), IyzicoGateway.cardToken(cardKey, result.paymentId()),
                    installments, "IYZICO", cardLabel);
        } catch (RuntimeException e) {
            // Refused before any payment was recorded, for instance because the business was suspended meanwhile.
            log.warn("Could not record iyzico payment {} for session {}; refunding it", result.paymentId(), sessionId, e);
            giveBack(result.paymentId(), visit.id());
            throw e;
        }
        if (paid.isEmpty()) {
            log.warn("Session {} moved on while its customer paid on iyzico; refunding payment {}", sessionId, result.paymentId());
            giveBack(result.paymentId(), visit.id());
            return;
        }
        // Refused by our own fraud rules after iyzico took the money: it goes straight back to the card.
        if ("DECLINED".equals(paid.get().paymentStatus())) {
            giveBack(result.paymentId(), visit.id());
        }
    }

    private static String mismatch(IyzicoClient.Result result, Visit visit, PublicSession view) {
        if (!visit.id().toString().equals(result.basketId()) || !visit.id().toString().equals(result.conversationId())) {
            return "it belongs to another visit";
        }
        if (result.price() == null || result.price().compareTo(IyzicoGateway.minor(view.amount())) != 0) {
            return "the amount differs";
        }
        if (!view.currency().equals(result.currency())) {
            return "the currency differs";
        }
        return null;
    }

    /** Settles the visit once. False when another request already did, which then owns the outcome. */
    private boolean settle(Visit visit, String status, String paymentId, String failure) {
        Integer updated = tx.execute(transaction -> {
            tenantScope.apply(visit.tenantId());
            return jdbc.sql("""
                            UPDATE iyzico_checkouts
                            SET status = :status, iyzico_payment_id = :paymentId, failure = :failure, completed_at = now()
                            WHERE id = :id AND status = 'STARTED'
                            """)
                    .param("status", status)
                    .param("paymentId", paymentId)
                    .param("failure", failure == null ? null : failure.length() > 500 ? failure.substring(0, 500) : failure)
                    .param("id", visit.id())
                    .update();
        });
        return updated == 1;
    }

    /** Cancels a charge we will not keep. A failure here is logged loudly: the customer is owed that money. */
    private void giveBack(String paymentId, UUID visitId) {
        try {
            client.cancel("cancel-" + visitId, paymentId);
        } catch (RuntimeException e) {
            log.error("Could not cancel iyzico payment {}; refund it from iyzico's panel", paymentId, e);
        }
    }

    private static String cardLabel(IyzicoClient.Result result) {
        String brand = switch (result.cardAssociation() == null ? "" : result.cardAssociation()) {
            case "MASTER_CARD" -> "Mastercard";
            case "VISA" -> "Visa";
            case "AMERICAN_EXPRESS" -> "American Express";
            case "TROY" -> "Troy";
            default -> "Kart";
        };
        return result.lastFourDigits() == null ? brand : brand + " •••• " + result.lastFourDigits();
    }
}
