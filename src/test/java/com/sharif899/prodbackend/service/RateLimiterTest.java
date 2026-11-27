package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The token bucket, tested with a clock the test moves by hand. */
class RateLimiterTest {

    /** A clock whose time the test advances instead of sleeping. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private RateLimiter limiter(int perMinute, MutableClock clock) {
        return new RateLimiter(clock, new AppProperties(
                new AppProperties.RateLimit(true, perMinute),
                new AppProperties.Cache("memory")));
    }

    @Test
    void allowsUpToTheLimitThenDenies() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = limiter(5, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        }
        var denied = limiter.tryConsume("client-a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refillsContinuouslyOverTime() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = limiter(6, clock); // 1 token every 10 seconds

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("client-a").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(20)); // 2 tokens refilled
        assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        assertThat(limiter.tryConsume("client-a").allowed()).isFalse();
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = limiter(1, clock); // 1 token a minute

        assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        long firstRetryAfter = limiter.tryConsume("client-a").retryAfterSeconds();
        assertThat(firstRetryAfter).isGreaterThanOrEqualTo(55);

        clock.advance(Duration.ofSeconds(30));
        long secondRetryAfter = limiter.tryConsume("client-a").retryAfterSeconds();
        assertThat(secondRetryAfter).isLessThan(firstRetryAfter);
        assertThat(secondRetryAfter).isGreaterThanOrEqualTo(25);
    }

    @Test
    void bucketsArePerKey() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = limiter(1, clock);

        assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        assertThat(limiter.tryConsume("client-a").allowed()).isFalse();
        assertThat(limiter.tryConsume("client-b").allowed()).isTrue();
        assertThat(limiter.tryConsume("client-b").allowed()).isFalse();
    }

    @Test
    void disabledLimiterAllowsEverything() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = new RateLimiter(clock, new AppProperties(
                new AppProperties.RateLimit(false, 1),
                new AppProperties.Cache("memory")));

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        }
    }

    @Test
    void remainingCountsDown() {
        var clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
        var limiter = limiter(3, clock);

        assertThat(limiter.tryConsume("client-a").remaining()).isEqualTo(2);
        assertThat(limiter.tryConsume("client-a").remaining()).isEqualTo(1);
        assertThat(limiter.tryConsume("client-a").remaining()).isEqualTo(0);
    }
}
