package com.kasa.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.kasa.common.Hashes;
import com.kasa.security.ApiKeyPrincipal;
import com.kasa.tenant.TenantScope;

import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditLog {

    public record Actor(String type, String id, String label) {

        public static final Actor SYSTEM = new Actor("SYSTEM", null, "Sistem");

        public static Actor user(UUID id, String email) {
            return new Actor("USER", id.toString(), email);
        }
    }

    public record Entry(long seq, OffsetDateTime createdAt, String actorType, String actorLabel, String action,
                        String targetType, String targetId, String metadata, String ip) {
    }

    public record EntryPage(List<Entry> items, Long nextCursor) {
    }

    /** {@code brokenAtSeq} is the first record that fails verification, or null when the whole chain holds. */
    public record ChainReport(boolean valid, long checked, Long brokenAtSeq) {
    }

    private record Stored(long seq, String createdAt, String actorType, String actorId, String actorLabel, String action,
                          String targetType, String targetId, String metadata, String ip, String prevHash, String hash) {
    }

    private static final String GENESIS = "0".repeat(64);
    private static final int VERIFY_BATCH = 1_000;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TenantScope tenantScope;

    public AuditLog(JdbcClient jdbc, JsonMapper jsonMapper, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.tenantScope = tenantScope;
    }

    /** Joins the caller's transaction, so an action and its record commit together or not at all. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID tenantId, String action, String targetType, Object targetId, Map<String, ?> metadata) {
        record(tenantId, currentActor(), action, targetType, targetId, metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID tenantId, Actor actor, String action, String targetType, Object targetId, Map<String, ?> metadata) {
        // The chain must be written in one order, so one tenant's records queue here until commit.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "audit:" + tenantId)
                .query((rs, rowNum) -> 1)
                .single();
        record Last(long seq, String hash) {
        }
        Last last = jdbc.sql("SELECT seq, hash FROM audit_log WHERE tenant_id = :tenantId ORDER BY seq DESC LIMIT 1")
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new Last(rs.getLong("seq"), rs.getString("hash")))
                .optional()
                .orElse(new Last(0, GENESIS));

        OffsetDateTime createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        Map<String, Object> ordered = new java.util.TreeMap<>(metadata);
        Stored entry = new Stored(last.seq() + 1, createdAt.format(TIMESTAMP), actor.type(), actor.id(), actor.label(), action,
                targetType, targetId == null ? null : targetId.toString(), jsonMapper.writeValueAsString(ordered), clientIp(),
                last.hash(), null);
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, seq, created_at, actor_type, actor_id, actor_label, action,
                                               target_type, target_id, metadata, ip, prev_hash, hash)
                        VALUES (:tenantId, :seq, :createdAt, :actorType, :actorId, :actorLabel, :action,
                                :targetType, :targetId, :metadata, :ip, :prevHash, :hash)
                        """)
                .param("tenantId", tenantId)
                .param("seq", entry.seq())
                .param("createdAt", createdAt)
                .param("actorType", entry.actorType())
                .param("actorId", entry.actorId())
                .param("actorLabel", entry.actorLabel())
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId())
                .param("metadata", entry.metadata())
                .param("ip", entry.ip())
                .param("prevHash", entry.prevHash())
                .param("hash", hashOf(tenantId, entry))
                .update();
    }

    @Transactional(readOnly = true)
    public EntryPage list(UUID tenantId, String action, Long before, int limit) {
        tenantScope.apply(tenantId);
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE true");
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit + 1);
        if (action != null) {
            sql.append(" AND action = :action");
            params.put("action", action);
        }
        if (before != null) {
            sql.append(" AND seq < :before");
            params.put("before", before);
        }
        sql.append(" ORDER BY seq DESC LIMIT :limit");
        List<Entry> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> new Entry(
                        rs.getLong("seq"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("actor_type"),
                        rs.getString("actor_label"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("metadata"),
                        rs.getString("ip")))
                .list();
        boolean hasMore = rows.size() > limit;
        List<Entry> page = hasMore ? rows.subList(0, limit) : rows;
        return new EntryPage(page, hasMore ? page.getLast().seq() : null);
    }

    /**
     * Walks the whole chain and recomputes every hash. It cannot notice removal of the newest records,
     * since nothing comes after them; that would need the latest hash anchored outside this database.
     */
    @Transactional(readOnly = true)
    public ChainReport verify(UUID tenantId) {
        tenantScope.apply(tenantId);
        String previousHash = GENESIS;
        long expectedSeq = 1;
        long checked = 0;
        while (true) {
            List<Stored> batch = jdbc.sql("""
                            SELECT seq, created_at, actor_type, actor_id, actor_label, action, target_type, target_id,
                                   metadata, ip, prev_hash, hash
                            FROM audit_log
                            WHERE seq >= :from
                            ORDER BY seq
                            LIMIT :limit
                            """)
                    .param("from", expectedSeq)
                    .param("limit", VERIFY_BATCH)
                    .query((rs, rowNum) -> new Stored(
                            rs.getLong("seq"),
                            rs.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).format(TIMESTAMP),
                            rs.getString("actor_type"),
                            rs.getString("actor_id"),
                            rs.getString("actor_label"),
                            rs.getString("action"),
                            rs.getString("target_type"),
                            rs.getString("target_id"),
                            rs.getString("metadata"),
                            rs.getString("ip"),
                            rs.getString("prev_hash"),
                            rs.getString("hash")))
                    .list();
            if (batch.isEmpty()) {
                return new ChainReport(true, checked, null);
            }
            for (Stored entry : batch) {
                boolean intact = entry.seq() == expectedSeq
                        && entry.prevHash().equals(previousHash)
                        && entry.hash().equals(hashOf(tenantId, entry));
                if (!intact) {
                    return new ChainReport(false, checked, expectedSeq);
                }
                previousHash = entry.hash();
                expectedSeq++;
                checked++;
            }
        }
    }

    /** Distinct action names, for the filter on the audit screen. */
    @Transactional(readOnly = true)
    public List<String> actions(UUID tenantId) {
        tenantScope.apply(tenantId);
        return new ArrayList<>(jdbc.sql("SELECT DISTINCT action FROM audit_log ORDER BY action").query(String.class).list());
    }

    private static String hashOf(UUID tenantId, Stored entry) {
        return Hashes.sha256Hex(String.join("\n", entry.prevHash(), tenantId.toString(), String.valueOf(entry.seq()),
                entry.createdAt(), entry.actorType(), nullToEmpty(entry.actorId()), nullToEmpty(entry.actorLabel()), entry.action(),
                nullToEmpty(entry.targetType()), nullToEmpty(entry.targetId()), entry.metadata(), nullToEmpty(entry.ip())));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return Actor.SYSTEM;
        }
        if (authentication.getPrincipal() instanceof ApiKeyPrincipal apiKey) {
            return new Actor("API_KEY", apiKey.prefix(), "API anahtarı " + apiKey.prefix());
        }
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            return new Actor("USER", jwt.getToken().getSubject(), jwt.getName());
        }
        return Actor.SYSTEM;
    }

    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
    }
}
