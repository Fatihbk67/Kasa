package com.kasa.iyzico;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys for iyzico's sandbox. Without both, the iyzico option is simply not offered and every payment goes
 * through the simulated bank, so the application runs the same with or without an iyzico account.
 */
@ConfigurationProperties("kasa.iyzico")
public record IyzicoProperties(String apiKey, String secretKey, String baseUrl) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
