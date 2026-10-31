package com.kasa.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slows password guessing and abuse of public endpoints. Counts are per instance and in memory, so with
 * several instances the effective limit multiplies; a shared store such as Redis would be needed to fix that.
 */
@Component
public class RateLimiter {

    private static final int MAX_TRACKED_KEYS = 100_000;

    private record Window(long startMillis, int count) {
    }

    private static final class TooManyRequests extends ResponseStatusException {

        private final long retryAfterSeconds;

        TooManyRequests(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "too many attempts, try again later");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            return headers;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long windowMillis;

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(Clock.systemUTC(), properties.window());
    }

    RateLimiter(Clock clock, Duration window) {
        this.clock = clock;
        this.windowMillis = window.toMillis();
    }

    /** Counts one attempt, and rejects it with 429 if the key has used up its limit in the current window. */
    public void check(String key, int limit) {
        long now = clock.millis();
        if (windows.size() >= MAX_TRACKED_KEYS) {
            windows.values().removeIf(window -> now - window.startMillis() >= windowMillis);
        }
        Window window = windows.compute(key, (k, current) -> current == null || now - current.startMillis() >= windowMillis
                ? new Window(now, 1)
                : new Window(current.startMillis(), current.count() + 1));
        if (window.count() > limit) {
            long remainingMillis = window.startMillis() + windowMillis - now;
            throw new TooManyRequests(Math.max(1, (remainingMillis + 999) / 1000));
        }
    }
}
