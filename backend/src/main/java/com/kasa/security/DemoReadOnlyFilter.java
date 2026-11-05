package com.kasa.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every visitor shares the demo's one account, so nobody may change it: not its password, two-step sign-in or
 * email, and not a role request that would land with the platform's administrators. The viewer role already
 * keeps money still; this keeps the rest still. Marking notifications read is allowed, since it changes nothing.
 */
final class DemoReadOnlyFilter extends OncePerRequestFilter {

    static final String DEMO_AUTHORITY = "DEMO";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean demo = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> DEMO_AUTHORITY.equals(authority.getAuthority()));
        if (demo && !READS.contains(request.getMethod()) && !request.getRequestURI().startsWith("/api/v1/notifications/")) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                    + "\"detail\":\"the demo account is read-only\",\"instance\":\"" + request.getRequestURI().replace("\"", "") + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
