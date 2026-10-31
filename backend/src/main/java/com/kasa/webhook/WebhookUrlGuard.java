package com.kasa.webhook;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Blocks server-side request forgery. A merchant chooses the URL and our server calls it, so without this
 * a merchant could aim requests at internal services or the cloud metadata address. Checked when an endpoint
 * is registered and again before every send, because a hostname's DNS answer can change after registration.
 */
@Component
public class WebhookUrlGuard {

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static class UnsafeUrlException extends RuntimeException {
        UnsafeUrlException(String message) {
            super(message);
        }
    }

    private final boolean allowInsecure;
    private final Resolver resolver;

    @Autowired
    public WebhookUrlGuard(WebhookProperties properties) {
        this(properties.allowInsecureTargets(), InetAddress::getAllByName);
    }

    WebhookUrlGuard(boolean allowInsecure, Resolver resolver) {
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    public URI check(String url) {
        URI uri = parse(url);
        if (uri.getRawUserInfo() != null) {
            throw new UnsafeUrlException("URL must not contain credentials");
        }
        if (allowInsecure) {
            return uri;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UnsafeUrlException("URL must use https");
        }
        try {
            for (InetAddress address : resolver.resolve(uri.getHost())) {
                if (isInternal(address)) {
                    throw new UnsafeUrlException("URL must not point to an internal address");
                }
            }
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("URL host could not be resolved");
        }
        return uri;
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("URL is malformed");
        }
        boolean httpLike = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        if (!httpLike || uri.getHost() == null) {
            throw new UnsafeUrlException("URL must be an absolute http or https URL");
        }
        return uri;
    }

    private static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7, unique local addresses, which isSiteLocalAddress does not cover.
            return (bytes[0] & 0xFE) == 0xFC;
        }
        // 100.64.0.0/10, shared address space used inside cloud networks.
        return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }
}
