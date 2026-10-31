package com.kasa.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final UUID tenantId;

    ApiKeyAuthentication(UUID tenantId) {
        super(List.of(new SimpleGrantedAuthority("SCOPE_" + SecurityConfig.API_SCOPE)));
        this.tenantId = tenantId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public UUID getPrincipal() {
        return tenantId;
    }
}
