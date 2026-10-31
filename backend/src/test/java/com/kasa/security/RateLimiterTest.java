package com.kasa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.support.Concurrency;

class RateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RateLimiter limiter = new RateLimiter(clock, Duration.ofMinutes(1));

    @Test
    void allowsUpToTheLimitThenRejectsWithRetryAfter() {
        limiter.check("key", 2);
        limiter.check("key", 2);
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> limiter.check("key", 2))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("40");
                });
    }

    @Test
    void aNewWindowStartsCountingAgain() {
        limiter.check("key", 1);
        clock.advance(Duration.ofMinutes(1));

        assertThatCode(() -> limiter.check("key", 1)).doesNotThrowAnyException();
    }

    @Test
    void keysAreCountedSeparately() {
        limiter.check("a", 1);

        assertThatCode(() -> limiter.check("b", 1)).doesNotThrowAnyException();
    }

    @Test
    void concurrentAttemptsCannotExceedTheLimit() throws Exception {
        AtomicInteger allowed = new AtomicInteger();

        List<Boolean> results = Concurrency.run(50, i -> () -> {
            try {
                limiter.check("key", 10);
                allowed.incrementAndGet();
                return true;
            } catch (ResponseStatusException e) {
                return false;
            }
        });

        assertThat(results).hasSize(50);
        assertThat(allowed).hasValue(10);
    }
}
