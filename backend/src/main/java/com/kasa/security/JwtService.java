package com.kasa.security;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    static final String ISSUER = "kasa";
    static final String TENANT_CLAIM = "tenant_id";
    static final String VERSION_CLAIM = "ver";
    static final String KIND_CLAIM = "kind";
    static final String ADMIN_KIND = "platform-admin";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** Raising a user's token version invalidates every token issued before it. */
    public String issue(UUID userId, UUID tenantId, int tokenVersion) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(VERSION_CLAIM, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Carries no tenant, so the token opens the platform's admin API and no merchant's data. */
    public String issueAdmin(UUID adminId, int tokenVersion) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(adminId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(KIND_CLAIM, ADMIN_KIND)
                .claim(VERSION_CLAIM, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }
}
