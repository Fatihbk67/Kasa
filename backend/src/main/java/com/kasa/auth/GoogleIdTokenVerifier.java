package com.kasa.auth;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks an ID token from Google's sign-in button: signed with one of Google's published keys, issued by Google,
 * meant for this application's client id and not expired. Only then is its email address believed.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
    }

    private final GoogleProperties properties;
    private volatile NimbusJwtDecoder decoder;

    public GoogleIdTokenVerifier(GoogleProperties properties) {
        this.properties = properties;
    }

    public GoogleIdentity verify(String idToken) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google sign-in is not configured");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            log.info("Rejected a Google ID token: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "the Google sign-in could not be verified");
        }
        Object verified = jwt.getClaims().get("email_verified");
        return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(verified) || "true".equals(verified), jwt.getClaimAsString("name"));
    }

    /** Built on first use, so an instance without Google configured never reaches out for Google's keys. */
    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl()).build();
                    built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(),
                            new JwtClaimValidator<Object>("iss", issuer -> issuer != null && ISSUERS.contains(issuer.toString())),
                            new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(properties.clientId()))));
                    decoder = built;
                }
                current = decoder;
            }
        }
        return current;
    }
}
