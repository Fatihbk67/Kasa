package com.kasa.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.common.Hashes;
import com.kasa.tenant.TenantScope;

@Service
public class ApiKeyService {

    public record ApiKeySummary(UUID id, String name, String prefix, OffsetDateTime createdAt,
                                OffsetDateTime revokedAt) {
    }

    /** The only time the full key is available: just its hash is stored. */
    public record CreatedApiKey(UUID id, String name, String prefix, String key) {
    }

    private record StoredKey(UUID tenantId, String keyHash) {
    }

    private static final Pattern KEY_FORMAT = Pattern.compile("kasa_test_([0-9a-f]{12})_[0-9a-f]{64}");

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TenantScope tenantScope;

    public ApiKeyService(JdbcClient jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public CreatedApiKey create(UUID tenantId, String name) {
        tenantScope.apply(tenantId);
        String prefix = randomHex(6);
        String key = "kasa_test_" + prefix + "_" + randomHex(32);
        UUID id = jdbc.sql("""
                        INSERT INTO api_keys (tenant_id, name, key_prefix, key_hash)
                        VALUES (:tenantId, :name, :prefix, :hash)
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("prefix", prefix)
                .param("hash", sha256(key))
                .query(UUID.class)
                .single();
        return new CreatedApiKey(id, name, prefix, key);
    }

    @Transactional(readOnly = true)
    public List<ApiKeySummary> list(UUID tenantId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("""
                        SELECT id, name, key_prefix, created_at, revoked_at
                        FROM api_keys
                        ORDER BY created_at DESC
                        """)
                .query((rs, rowNum) -> new ApiKeySummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("key_prefix"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        tenantScope.apply(tenantId);
        int updated = jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", keyId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "active API key not found");
        }
    }

    /** Returns the owning tenant if the key is valid and not revoked. */
    public Optional<UUID> authenticate(String key) {
        Matcher matcher = KEY_FORMAT.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        byte[] presentedHash = sha256(key).getBytes(StandardCharsets.UTF_8);
        return jdbc.sql("SELECT tenant_id, key_hash FROM find_active_api_key(:prefix)")
                .param("prefix", matcher.group(1))
                .query((rs, rowNum) -> new StoredKey(rs.getObject("tenant_id", UUID.class), rs.getString("key_hash")))
                .optional()
                .filter(stored -> MessageDigest.isEqual(presentedHash, stored.keyHash().getBytes(StandardCharsets.UTF_8)))
                .map(StoredKey::tenantId);
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    // A fast hash is safe here: unlike a password, the key has 256 random bits, so guessing is not feasible.
    private static String sha256(String value) {
        return Hashes.sha256Hex(value);
    }
}
