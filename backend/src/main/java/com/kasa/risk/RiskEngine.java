package com.kasa.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kasa.common.Hashes;

/**
 * Decides, before the bank is asked, whether a payment goes ahead, waits for a person, or is refused.
 * It runs inside the transaction that creates the payment, so the payment it counts and the payment it decides
 * on are written together.
 */
@Component
public class RiskEngine {

    public enum Level { NORMAL, REVIEW, BLOCKED }

    /**
     * How strictly a payment is screened. A subscription renewal is charged by the business, with no customer
     * waiting and nobody to answer a hold, so review rules do not apply to it. Blocks still do.
     */
    public enum Screening { FULL, BLOCK_ONLY }

    public record Assessment(Level level, List<String> reasons) {

        public boolean blocked() {
            return level == Level.BLOCKED;
        }

        public boolean held() {
            return level == Level.REVIEW;
        }
    }

    public static final String BLOCKLISTED = "CARD_BLOCKLISTED";
    public static final String RISK_BLOCKED = "risk_blocked";

    private record Rule(String kind, long threshold, Integer windowMinutes, String currency, String action) {
    }

    private final JdbcClient jdbc;

    public RiskEngine(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The token stands for the card, so its hash recognises the card again without keeping anything usable. */
    public static String fingerprint(String cardToken) {
        // An iyzico token ends with the payment it belongs to; only the card part before it identifies the card.
        if (cardToken.startsWith("iyzico:")) {
            return Hashes.sha256Hex("card:" + cardToken.substring(0, cardToken.lastIndexOf(':')));
        }
        return Hashes.sha256Hex("card:" + cardToken);
    }

    /** Expects the tenant to be bound. Must run before the payment being assessed is inserted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Assessment assess(UUID tenantId, String fingerprint, long amount, String currency, Screening screening) {
        List<Rule> rules = jdbc.sql("SELECT kind, threshold, window_minutes, currency, action FROM risk_rules WHERE enabled")
                .query((rs, rowNum) -> new Rule(rs.getString("kind"), rs.getLong("threshold"),
                        rs.getObject("window_minutes", Integer.class), rs.getString("currency"), rs.getString("action")))
                .list();
        if (screening == Screening.BLOCK_ONLY) {
            rules = rules.stream().filter(rule -> rule.action().equals("BLOCK")).toList();
        }
        if (rules.stream().anyMatch(rule -> !rule.kind().equals("AMOUNT_ABOVE"))) {
            // Without this, twenty payments with one card sent at the same moment would each count the other nineteen
            // as not there yet, and all pass a limit of five. Only the same card of the same business waits.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "risk:" + tenantId + ":" + fingerprint)
                    .query((rs, rowNum) -> 1)
                    .single();
        }

        List<String> blocks = new ArrayList<>();
        List<String> holds = new ArrayList<>();
        if (isBlocklisted(fingerprint)) {
            blocks.add(BLOCKLISTED);
        }
        for (Rule rule : rules) {
            if (matches(rule, fingerprint, amount, currency)) {
                (rule.action().equals("BLOCK") ? blocks : holds).add(rule.kind());
            }
        }
        if (!blocks.isEmpty()) {
            return new Assessment(Level.BLOCKED, distinct(blocks));
        }
        return holds.isEmpty() ? new Assessment(Level.NORMAL, List.of()) : new Assessment(Level.REVIEW, distinct(holds));
    }

    private boolean matches(Rule rule, String fingerprint, long amount, String currency) {
        return switch (rule.kind()) {
            case "AMOUNT_ABOVE" -> rule.currency().equals(currency) && amount > rule.threshold();
            // The limit is how many earlier attempts are allowed; the one after them is the one that trips it.
            case "CARD_VELOCITY" -> recent(fingerprint, rule.windowMinutes(), false) >= rule.threshold();
            case "CARD_DECLINES" -> recent(fingerprint, rule.windowMinutes(), true) >= rule.threshold();
            default -> false;
        };
    }

    private long recent(String fingerprint, int windowMinutes, boolean declinedByBank) {
        // Blocked attempts count towards velocity, so a card that keeps hammering stays blocked. They do not count
        // as declines, because the bank never saw them and a block must not feed on its own decisions.
        String condition = declinedByBank ? " AND status = 'DECLINED' AND failure_code IS DISTINCT FROM '" + RISK_BLOCKED + "'" : "";
        return jdbc.sql("""
                        SELECT COUNT(*) FROM payments
                        WHERE card_fingerprint = :fingerprint AND created_at > now() - make_interval(mins => :minutes)
                        """ + condition)
                .param("fingerprint", fingerprint)
                .param("minutes", windowMinutes)
                .query(Long.class)
                .single();
    }

    private boolean isBlocklisted(String fingerprint) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM risk_blocklist WHERE card_fingerprint = :fingerprint)")
                .param("fingerprint", fingerprint)
                .query(Boolean.class)
                .single();
    }

    private static List<String> distinct(List<String> reasons) {
        return reasons.stream().distinct().toList();
    }
}
