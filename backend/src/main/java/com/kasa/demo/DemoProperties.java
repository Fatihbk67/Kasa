package com.kasa.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** A shared, read-only sample business that anyone can open from the sign-in page without an account. */
@ConfigurationProperties("kasa.demo")
public record DemoProperties(boolean enabled) {
}
