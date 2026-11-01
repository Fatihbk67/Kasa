package com.kasa.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKeyPrincipal principal;

    ApiKeyAuthentication(ApiKeyPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("SCOPE_" + SecurityConfig.API_SCOPE)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }
}
