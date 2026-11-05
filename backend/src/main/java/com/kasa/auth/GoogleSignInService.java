package com.kasa.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.security.JwtProperties;

/**
 * Signs a person in with Google, or, when no account uses their address yet, hands back a short-lived signup
 * ticket. The ticket carries the address Google confirmed to the one question left, the business name, so the
 * browser never gets to choose which address the new account receives.
 */
@Service
public class GoogleSignInService {

    static final String PROVIDER = "google";
    /** Not the issuer of dashboard tokens, so a ticket can never be used to call the API. */
    private static final String SIGNUP_ISSUER = "kasa-google-signup";
    private static final Duration SIGNUP_TTL = Duration.ofMinutes(15);

    public record SignIn(AuthService.LoginResponse login, boolean registrationRequired, String signupToken, String email,
                         String suggestedName) {
    }

    private final GoogleIdTokenVerifier verifier;
    private final AuthService auth;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder signupDecoder;

    public GoogleSignInService(GoogleIdTokenVerifier verifier, AuthService auth, JwtEncoder encoder, JwtProperties jwt) {
        this.verifier = verifier;
        this.auth = auth;
        this.encoder = encoder;
        this.signupDecoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.signupDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(SIGNUP_ISSUER));
    }

    public SignIn signIn(String idToken) {
        GoogleIdTokenVerifier.GoogleIdentity identity = verifier.verify(idToken);
        if (identity.email() == null || !identity.emailVerified()) {
            // Anyone can put any address on a Google account; only one Google has confirmed may open an account.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google has not confirmed this email address");
        }
        return auth.loginVerified(identity.email(), PROVIDER)
                .map(login -> new SignIn(login, false, null, null, null))
                .orElseGet(() -> new SignIn(null, true, signupToken(identity.email()), AuthService.normalize(identity.email()),
                        identity.name()));
    }

    public AuthService.TokenResponse register(String signupToken, String businessName) {
        Jwt ticket;
        try {
            ticket = signupDecoder.decode(signupToken);
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "the Google sign-up has expired; please start again");
        }
        return auth.registerVerified(businessName.trim(), ticket.getSubject(), PROVIDER);
    }

    private String signupToken(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SIGNUP_ISSUER)
                .id(UUID.randomUUID().toString())
                .subject(AuthService.normalize(email))
                .issuedAt(now)
                .expiresAt(now.plus(SIGNUP_TTL))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
