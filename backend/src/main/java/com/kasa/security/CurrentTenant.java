package com.kasa.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The tenant always comes from verified credentials, never from a request parameter. */
public final class CurrentTenant {

    private CurrentTenant() {
    }

    public static UUID of(Authentication authentication) {
        return switch (authentication) {
            case ApiKeyAuthentication apiKey -> apiKey.getPrincipal();
            case JwtAuthenticationToken jwt -> UUID.fromString(jwt.getToken().getClaimAsString(JwtService.TENANT_CLAIM));
            default -> throw new IllegalStateException("unsupported authentication: " + authentication.getClass());
        };
    }
}
