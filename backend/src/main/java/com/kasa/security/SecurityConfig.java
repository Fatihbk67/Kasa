package com.kasa.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
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

import com.kasa.apikey.ApiKeyService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfig {

    static final String DASHBOARD_SCOPE = "dashboard";
    static final String API_SCOPE = "api";
    static final String ADMIN_SCOPE = "admin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyService apiKeys,
                                            AdminProperties adminProperties) throws Exception {
        // Created here rather than as beans, so Spring Boot does not also register them for every servlet request.
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(apiKeys);
        AdminTokenFilter adminTokenFilter = new AdminTokenFilter(adminProperties);
        return http
                // No cookies are used, so browsers cannot attach credentials on their own and CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/auth/**", "/api/v1/public/**", "/actuator/health", "/error").permitAll()
                        // A leaked API key must not be able to mint new keys or redirect webhooks.
                        .requestMatchers("/api/v1/api-keys/**", "/api/v1/webhook-endpoints/**")
                        .hasAuthority("SCOPE_" + DASHBOARD_SCOPE)
                        .requestMatchers("/api/v1/admin/**").hasAuthority("SCOPE_" + ADMIN_SCOPE)
                        // An admin token carries no tenant, so it opens nothing but the admin API.
                        .anyRequest().hasAnyAuthority("SCOPE_" + DASHBOARD_SCOPE, "SCOPE_" + API_SCOPE))
                .addFilterBefore(adminTokenFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
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
