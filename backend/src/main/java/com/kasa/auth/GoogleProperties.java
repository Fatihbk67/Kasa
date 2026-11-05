package com.kasa.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "Sign in with Google". The client id is public by design: it is printed in every page that shows Google's button,
 * and an ID token proves only that Google issued it for this id. No client secret is involved.
 */
@ConfigurationProperties("kasa.google")
public record GoogleProperties(String clientId, String jwksUrl) {

    public boolean enabled() {
        return clientId != null && !clientId.isBlank();
    }
}
