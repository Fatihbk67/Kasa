package com.kasa.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kasa.admin")
public record AdminProperties(String token) {
}
