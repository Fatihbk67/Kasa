package com.kasa.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.kasa.apikey.ApiKeyService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfig {

    static final String DASHBOARD_SCOPE = "dashboard";
    static final String API_SCOPE = "api";
    static final String ADMIN_SCOPE = "admin";

    private static final String OWNER = "ROLE_OWNER";
    private static final String MANAGER = "ROLE_ADMIN";
    private static final String VIEWER = "ROLE_VIEWER";

    // Limits the damage of any script injection, which matters because the dashboard's token is reachable from script.
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; object-src 'none'; "
            + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyService apiKeys, AdminProperties adminProperties,
                                            UserAccessConverter userAccess) throws Exception {
        // Created here rather than as beans, so Spring Boot does not also register them for every servlet request.
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(apiKeys);
        AdminTokenFilter adminTokenFilter = new AdminTokenFilter(adminProperties);
        return http
                // No cookies are used, so browsers cannot attach credentials on their own and CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/auth/**", "/api/v1/public/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_" + ADMIN_SCOPE)
                        .requestMatchers("/api/v1/admin/**").hasAuthority("SCOPE_" + ADMIN_SCOPE)
                        .requestMatchers("/api/v1/me", "/api/v1/settings/password", "/api/v1/settings/two-factor/**",
                                "/api/v1/settings/role-request", "/api/v1/notifications/**").hasAnyAuthority(OWNER, MANAGER, VIEWER)
                        .requestMatchers("/api/v1/settings/**").hasAuthority(OWNER)
                        // A leaked API key must not be able to mint new keys, redirect webhooks or add members.
                        .requestMatchers("/api/v1/api-keys/**", "/api/v1/webhook-endpoints/**", "/api/v1/team/**",
                                "/api/v1/audit-log/**").hasAnyAuthority(OWNER, MANAGER)
                        // An admin token carries no tenant, so it opens nothing but the admin API.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyAuthority("SCOPE_" + DASHBOARD_SCOPE, "SCOPE_" + API_SCOPE)
                        // Everything that changes data: a viewer can look but never move money.
                        .requestMatchers("/api/**").hasAnyAuthority(OWNER, MANAGER, "SCOPE_" + API_SCOPE)
                        // Everything else is the public web interface, which holds no data of its own.
                        .anyRequest().permitAll())
                // A password reset link holds its token in the path, which must not leak to other sites in a Referer header.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .addFilterBefore(adminTokenFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(userAccess)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
        return decoder;
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
