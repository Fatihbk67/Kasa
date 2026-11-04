package com.kasa.risk;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentViews.PaymentView;
import com.kasa.tenant.TenantScope;

/** The business's own fraud settings: its rules, the cards it refuses, and the payments waiting for a decision. */
@Service
public class RiskService {

    public record RuleInput(String kind, long threshold, Integer windowMinutes, String currency, String action, Boolean enabled) {
    }

    public record RuleView(UUID id, String kind, long threshold, Integer windowMinutes, String currency, String action,
                           boolean enabled, OffsetDateTime createdAt) {
    }

    public record BlockedCard(String fingerprint, String reason, UUID sourcePayment, OffsetDateTime createdAt) {
    }

    private static final Set<String> KINDS = Set.of("CARD_VELOCITY", "CARD_DECLINES", "AMOUNT_ABOVE");
    private static final Set<String> ACTIONS = Set.of("REVIEW", "BLOCK");
    private static final int MAX_RULES = 20;
    private static final int REVIEW_QUEUE_LIMIT = 100;

    /** A starting point any small business can live with, which it can then tune. */
    private static final List<RuleInput> RECOMMENDED = List.of(
            new RuleInput("CARD_VELOCITY", 5, 10, null, "BLOCK", true),
            new RuleInput("CARD_DECLINES", 3, 60, null, "BLOCK", true),
            new RuleInput("AMOUNT_ABOVE", 1_000_000, null, "TRY", "REVIEW", true));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final PaymentService payments;

    public RiskService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog, PaymentService payments) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.payments = payments;
    }

    public List<RuleView> rules(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return listRules();
        });
    }

    public RuleView addRule(UUID tenantId, RuleInput input) {
        RuleInput rule = validated(input);
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            // Locked so two additions at once cannot both slip under the limit.
            jdbc.sql("SELECT id FROM tenants FOR UPDATE").query(UUID.class).list();
            if (jdbc.sql("SELECT COUNT(*) FROM risk_rules").query(Long.class).single() >= MAX_RULES) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a business can have at most " + MAX_RULES + " rules");
            }
            UUID id = insert(tenantId, rule);
            auditLog.record(tenantId, "RISK_RULE_CREATED", "RISK_RULE", id, describe(rule));
            return find(id);
        });
    }

    /** Fails if the business already has rules, so pressing the button twice cannot double them. */
    public List<RuleView> addRecommended(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("SELECT id FROM tenants FOR UPDATE").query(UUID.class).list();
            if (jdbc.sql("SELECT COUNT(*) FROM risk_rules").query(Long.class).single() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "recommended rules are only added to an empty rule set");
            }
            for (RuleInput rule : RECOMMENDED) {
                UUID id = insert(tenantId, rule);
                auditLog.record(tenantId, "RISK_RULE_CREATED", "RISK_RULE", id, describe(rule));
            }
            return listRules();
        });
    }

    public RuleView updateRule(UUID tenantId, UUID ruleId, RuleInput input) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            String kind = jdbc.sql("SELECT kind FROM risk_rules WHERE id = :id FOR UPDATE")
                    .param("id", ruleId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(RiskService::ruleNotFound);
            // The kind is what the rule is; changing it would be a different rule, so it is kept.
            RuleInput rule = validated(new RuleInput(kind, input.threshold(), input.windowMinutes(), input.currency(),
                    input.action(), input.enabled()));
            jdbc.sql("""
                            UPDATE risk_rules
                            SET threshold = :threshold, window_minutes = :window, currency = :currency, action = :action,
                                enabled = :enabled, updated_at = now()
                            WHERE id = :id
                            """)
                    .param("threshold", rule.threshold())
                    .param("window", rule.windowMinutes())
                    .param("currency", rule.currency())
                    .param("action", rule.action())
                    .param("enabled", rule.enabled())
                    .param("id", ruleId)
                    .update();
            auditLog.record(tenantId, "RISK_RULE_UPDATED", "RISK_RULE", ruleId, describe(rule));
            return find(ruleId);
        });
    }

    public void deleteRule(UUID tenantId, UUID ruleId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            if (jdbc.sql("DELETE FROM risk_rules WHERE id = :id").param("id", ruleId).update() == 0) {
                throw ruleNotFound();
            }
            auditLog.record(tenantId, "RISK_RULE_DELETED", "RISK_RULE", ruleId, Map.of());
        });
    }

    public List<BlockedCard> blocklist(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT * FROM risk_blocklist ORDER BY created_at DESC")
                    .query((rs, rowNum) -> new BlockedCard(rs.getString("card_fingerprint"), rs.getString("reason"),
                            rs.getObject("source_payment", UUID.class), rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
        });
    }

    /** A card is blocked by pointing at one of its payments, because the business never sees the card itself. */
    public BlockedCard blockCardOf(UUID tenantId, UUID paymentId, String reason) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            String fingerprint = jdbc.sql("SELECT card_fingerprint FROM payments WHERE id = :id")
                    .param("id", paymentId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));
            if (fingerprint == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this payment was made before cards were recognised");
            }
            try {
                jdbc.sql("INSERT INTO risk_blocklist (tenant_id, card_fingerprint, reason, source_payment) VALUES (:t, :f, :r, :p)")
                        .param("t", tenantId)
                        .param("f", fingerprint)
                        .param("r", reason)
                        .param("p", paymentId)
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this card is already blocked");
            }
            auditLog.record(tenantId, "CARD_BLOCKED", "PAYMENT", paymentId, Map.of("reason", reason));
            return new BlockedCard(fingerprint, reason, paymentId, OffsetDateTime.now());
        });
    }

    public void unblock(UUID tenantId, String fingerprint) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            if (jdbc.sql("DELETE FROM risk_blocklist WHERE card_fingerprint = :f").param("f", fingerprint).update() == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "this card is not blocked");
            }
            auditLog.record(tenantId, "CARD_UNBLOCKED", "CARD", fingerprint.substring(0, 12), Map.of());
        });
    }

    public List<PaymentView> reviewQueue(UUID tenantId) {
        return payments.heldForReview(tenantId, REVIEW_QUEUE_LIMIT);
    }

    /** Approving can collect the money in the same step; rejecting can also refuse the card from now on. */
    public PaymentView decide(UUID tenantId, UUID userId, UUID paymentId, boolean approve, boolean capture, boolean blockCard) {
        String reviewer = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT email FROM users WHERE id = :id").param("id", userId).query(String.class).single();
        });
        PaymentView decided = payments.decideReview(tenantId, paymentId, reviewer, approve);
        if (!approve && blockCard) {
            try {
                blockCardOf(tenantId, paymentId, "İncelemede reddedildi");
            } catch (ResponseStatusException alreadyBlocked) {
                // The card was blocked meanwhile, which is what was asked for.
            }
        }
        if (approve && capture) {
            return payments.capture(tenantId, paymentId, "review-capture:" + paymentId, null).payment();
        }
        return decided;
    }

    private List<RuleView> listRules() {
        return jdbc.sql("SELECT * FROM risk_rules ORDER BY created_at, id").query(RiskService::mapRule).list();
    }

    private RuleView find(UUID id) {
        return jdbc.sql("SELECT * FROM risk_rules WHERE id = :id").param("id", id).query(RiskService::mapRule).single();
    }

    private UUID insert(UUID tenantId, RuleInput rule) {
        return jdbc.sql("""
                        INSERT INTO risk_rules (tenant_id, kind, threshold, window_minutes, currency, action, enabled, created_at)
                        VALUES (:tenantId, :kind, :threshold, :window, :currency, :action, :enabled, clock_timestamp())
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("kind", rule.kind())
                .param("threshold", rule.threshold())
                .param("window", rule.windowMinutes())
                .param("currency", rule.currency())
                .param("action", rule.action())
                .param("enabled", rule.enabled())
                .query(UUID.class)
                .single();
    }

    private static RuleInput validated(RuleInput input) {
        if (!KINDS.contains(input.kind()) || !ACTIONS.contains(input.action())) {
            throw invalid("unknown rule kind or action");
        }
        if (input.threshold() < 1) {
            throw invalid("the threshold must be positive");
        }
        boolean amountRule = input.kind().equals("AMOUNT_ABOVE");
        if (amountRule) {
            if (input.currency() == null || !input.currency().matches("[A-Z]{3}")) {
                throw invalid("an amount rule needs a currency");
            }
            return new RuleInput(input.kind(), input.threshold(), null, input.currency(), input.action(), enabledOf(input));
        }
        if (input.windowMinutes() == null || input.windowMinutes() < 1 || input.windowMinutes() > 10_080) {
            throw invalid("a card rule needs a window between 1 minute and 7 days");
        }
        if (input.threshold() > 1_000) {
            throw invalid("a card rule counts at most 1000 attempts");
        }
        return new RuleInput(input.kind(), input.threshold(), input.windowMinutes(), null, input.action(), enabledOf(input));
    }

    private static boolean enabledOf(RuleInput input) {
        return input.enabled() == null || input.enabled();
    }

    private static Map<String, Object> describe(RuleInput rule) {
        return rule.windowMinutes() == null
                ? Map.of("kind", rule.kind(), "threshold", rule.threshold(), "currency", rule.currency(), "action", rule.action(), "enabled", rule.enabled())
                : Map.of("kind", rule.kind(), "threshold", rule.threshold(), "windowMinutes", rule.windowMinutes(), "action", rule.action(), "enabled", rule.enabled());
    }

    private static RuleView mapRule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RuleView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getLong("threshold"),
                rs.getObject("window_minutes", Integer.class), rs.getString("currency"), rs.getString("action"),
                rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static ResponseStatusException ruleNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
