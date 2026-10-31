package com.kasa.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kasa.common.Hashes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AdminTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Admin-Token";

    private static final class AdminAuthentication extends AbstractAuthenticationToken {

        AdminAuthentication() {
            super(List.of(new SimpleGrantedAuthority("SCOPE_" + SecurityConfig.ADMIN_SCOPE)));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return "platform-admin";
        }
    }

    private final AdminProperties properties;

    AdminTokenFilter(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!matches(presented)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AdminAuthentication());
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    // Comparing fixed-length hashes in constant time reveals neither the token's content nor its length.
    private boolean matches(String presented) {
        String configured = properties.token();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                Hashes.sha256Hex(presented).getBytes(StandardCharsets.UTF_8),
                Hashes.sha256Hex(configured).getBytes(StandardCharsets.UTF_8));
    }
}
