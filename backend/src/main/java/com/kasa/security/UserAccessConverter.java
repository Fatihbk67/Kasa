package com.kasa.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.kasa.demo.DemoAccountService;

/**
 * Takes a user's permissions from the database on every request instead of from the token. A signed token
 * cannot be recalled, so this is what makes removing a member, lowering a role or changing a password
 * take effect immediately. The cost is one small query per dashboard request; API keys are unaffected.
 */
@Component
class UserAccessConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private record Access(UUID tenantId, String email, String role, int tokenVersion) {
    }

    private final JdbcClient jdbc;

    UserAccessConverter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (JwtService.ADMIN_KIND.equals(jwt.getClaimAsString(JwtService.KIND_CLAIM))) {
            return convertAdmin(jwt);
        }
        Access access = jdbc.sql("SELECT tenant_id, email, role, token_version FROM find_user_access(:id)")
                .param("id", UUID.fromString(jwt.getSubject()))
                .query((rs, rowNum) -> new Access(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getInt("token_version")))
                .optional()
                .orElseThrow(() -> new BadCredentialsException("user no longer has access"));

        Long tokenVersion = jwt.getClaim(JwtService.VERSION_CLAIM);
        boolean sameTenant = access.tenantId().toString().equals(jwt.getClaimAsString(JwtService.TENANT_CLAIM));
        if (tokenVersion == null || tokenVersion != access.tokenVersion() || !sameTenant) {
            throw new BadCredentialsException("token has been revoked");
        }
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>(List.of(
                new SimpleGrantedAuthority("ROLE_" + access.role()),
                new SimpleGrantedAuthority("SCOPE_" + SecurityConfig.DASHBOARD_SCOPE)));
        if (DemoAccountService.VISITOR_EMAIL.equals(access.email())) {
            authorities.add(new SimpleGrantedAuthority(DemoReadOnlyFilter.DEMO_AUTHORITY));
        }
        return new JwtAuthenticationToken(jwt, authorities, access.email());
    }

    private AbstractAuthenticationToken convertAdmin(Jwt jwt) {
        record Admin(String email, int tokenVersion) {
        }
        Admin admin = jdbc.sql("SELECT email, token_version FROM platform_admins WHERE id = :id AND disabled_at IS NULL")
                .param("id", UUID.fromString(jwt.getSubject()))
                .query((rs, rowNum) -> new Admin(rs.getString("email"), rs.getInt("token_version")))
                .optional()
                .orElseThrow(() -> new BadCredentialsException("administrator no longer has access"));
        Long tokenVersion = jwt.getClaim(JwtService.VERSION_CLAIM);
        if (tokenVersion == null || tokenVersion != admin.tokenVersion()) {
            throw new BadCredentialsException("token has been revoked");
        }
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_" + SecurityConfig.ADMIN_SCOPE)), admin.email());
    }
}
