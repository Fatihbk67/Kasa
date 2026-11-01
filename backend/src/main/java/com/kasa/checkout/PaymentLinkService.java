package com.kasa.checkout;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.customer.CustomerService;
import com.kasa.tenant.TenantScope;

/** A reusable link: every visitor gets their own checkout session, and the sessions do the actual charging. */
@Service
public class PaymentLinkService {

    public record LinkView(UUID id, String slug, String title, String description, Long amount, String currency,
                           Integer maxPayments, OffsetDateTime expiresAt, String status, long paidCount, long collected,
                           OffsetDateTime createdAt) {
    }

    /** What an anonymous visitor may see. The tenant id stays internal. */
    public record PublicLink(String merchantName, String title, String description, Long amount, String currency, String status) {
    }

    public record NewLink(String title, String description, Long amount, String currency, Integer maxPayments,
                          OffsetDateTime expiresAt) {
    }

    private record StoredLink(UUID id, UUID tenantId, String merchantName, String title, String description, Long amount,
                              String currency, Integer maxPayments, OffsetDateTime expiresAt, OffsetDateTime disabledAt) {
    }

    private static final int LIST_LIMIT = 100;
    private static final int SLUG_LENGTH = 16;
    private static final String SLUG_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final CustomerService customers;
    private final CheckoutService checkout;

    public PaymentLinkService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog,
                              CustomerService customers, CheckoutService checkout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.customers = customers;
        this.checkout = checkout;
    }

    public LinkView create(UUID tenantId, NewLink link) {
        UUID id = UUID.randomUUID();
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("""
                            INSERT INTO payment_links (id, tenant_id, slug, title, description, amount, currency, max_payments, expires_at)
                            VALUES (:id, :tenantId, :slug, :title, :description, :amount, :currency, :maxPayments, :expiresAt)
                            """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("slug", newSlug())
                    .param("title", link.title())
                    .param("description", link.description())
                    .param("amount", link.amount())
                    .param("currency", link.currency())
                    .param("maxPayments", link.maxPayments())
                    .param("expiresAt", link.expiresAt())
                    .update();
            auditLog.record(tenantId, "PAYMENT_LINK_CREATED", "PAYMENT_LINK", id, Map.of("title", link.title()));
            return find(id);
        });
    }

    /** Sessions already open can no longer be paid either: the check runs again when a customer pays. */
    public LinkView disable(UUID tenantId, UUID id) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("""
                            UPDATE payment_links SET disabled_at = now(), updated_at = now()
                            WHERE id = :id AND disabled_at IS NULL
                            """)
                    .param("id", id)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "active payment link not found");
            }
            auditLog.record(tenantId, "PAYMENT_LINK_DISABLED", "PAYMENT_LINK", id, Map.of());
            return find(id);
        });
    }

    public List<LinkView> list(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql(LINK_QUERY + " ORDER BY l.created_at DESC, l.id DESC LIMIT :limit")
                    .param("limit", LIST_LIMIT)
                    .query(PaymentLinkService::mapLink)
                    .list();
        });
    }

    public PublicLink getPublic(String slug) {
        StoredLink link = load(slug);
        return tx.execute(status -> {
            tenantScope.apply(link.tenantId());
            return new PublicLink(link.merchantName(), link.title(), link.description(), link.amount(), link.currency(),
                    availability(link.id(), link.maxPayments(), link.expiresAt(), link.disabledAt()));
        });
    }

    /**
     * Opens a session for one visitor. Places are checked here only to give a quick answer: the check that counts
     * runs when the customer pays, because many people can hold an open session for the last place.
     */
    public CheckoutService.CreatedSession startSession(String slug, String email, String name, Long requestedAmount) {
        StoredLink link = load(slug);
        if (link.amount() != null && requestedAmount != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "this link has a fixed amount");
        }
        long amount = link.amount() != null ? link.amount() : requireAmount(requestedAmount);
        return tx.execute(status -> {
            tenantScope.apply(link.tenantId());
            String availability = availability(link.id(), link.maxPayments(), link.expiresAt(), link.disabledAt());
            if (!availability.equals("OPEN")) {
                throw new ResponseStatusException(HttpStatus.GONE, "this payment link is no longer available");
            }
            UUID customerId = customers.findOrCreate(link.tenantId(), email, name);
            return checkout.createForLink(link.tenantId(), amount, link.currency(), link.title(), link.id(), customerId);
        });
    }

    private static long requireAmount(Long amount) {
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "amount is required");
        }
        return amount;
    }

    private String availability(UUID linkId, Integer maxPayments, OffsetDateTime expiresAt, OffsetDateTime disabledAt) {
        return availability(maxPayments, expiresAt, disabledAt, checkout.placesTaken(linkId));
    }

    private static String availability(Integer maxPayments, OffsetDateTime expiresAt, OffsetDateTime disabledAt, long placesTaken) {
        if (disabledAt != null) {
            return "DISABLED";
        }
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            return "EXPIRED";
        }
        return maxPayments != null && placesTaken >= maxPayments ? "SOLD_OUT" : "OPEN";
    }

    private LinkView find(UUID id) {
        return jdbc.sql(LINK_QUERY + " WHERE l.id = :id")
                .param("id", id)
                .query(PaymentLinkService::mapLink)
                .single();
    }

    private StoredLink load(String slug) {
        return jdbc.sql("SELECT * FROM find_payment_link(:slug)")
                .param("slug", slug)
                .query((rs, rowNum) -> new StoredLink(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("merchant_name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getObject("amount", Long.class),
                        rs.getString("currency"),
                        rs.getObject("max_payments", Integer.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("disabled_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment link not found"));
    }

    private String newSlug() {
        StringBuilder slug = new StringBuilder(SLUG_LENGTH);
        for (int i = 0; i < SLUG_LENGTH; i++) {
            slug.append(SLUG_ALPHABET.charAt(random.nextInt(SLUG_ALPHABET.length())));
        }
        return slug.toString();
    }

    private static final String LINK_QUERY = """
            SELECT l.*, s.paid_count, s.collected, s.places_taken
            FROM payment_links l
            CROSS JOIN LATERAL (
                SELECT COUNT(*) FILTER (WHERE p.captured_amount > 0) AS paid_count,
                       COALESCE(SUM(p.captured_amount - p.refunded_amount - p.disputed_amount), 0) AS collected,
                       COUNT(*) FILTER (WHERE x.status = 'PROCESSING' AND x.expires_at > now()
                                           OR x.status = 'COMPLETED' AND p.status NOT IN ('DECLINED', 'FAILED')) AS places_taken
                FROM checkout_sessions x
                LEFT JOIN payments p ON p.id = x.payment_id
                WHERE x.payment_link_id = l.id
            ) s
            """;

    private static LinkView mapLink(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
        Integer maxPayments = rs.getObject("max_payments", Integer.class);
        String status = availability(maxPayments, expiresAt, rs.getObject("disabled_at", OffsetDateTime.class), rs.getLong("places_taken"));
        return new LinkView(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("amount", Long.class),
                rs.getString("currency"),
                maxPayments,
                expiresAt,
                status,
                rs.getLong("paid_count"),
                rs.getLong("collected"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
