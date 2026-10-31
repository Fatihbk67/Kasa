package com.kasa.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kasa.rate-limit")
public record RateLimitProperties(Duration window, int loginPerIpAndEmail, int loginPerIp, int registerPerIp,
                                  int checkoutPayPerIp) {
}
