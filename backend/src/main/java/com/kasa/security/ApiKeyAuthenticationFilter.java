package com.kasa.security;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kasa.apikey.ApiKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Api-Key";

    private final ApiKeyService apiKeys;

    ApiKeyAuthenticationFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiKeyPrincipal> principal = apiKeys.authenticate(key);
        if (principal.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(principal.get()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
