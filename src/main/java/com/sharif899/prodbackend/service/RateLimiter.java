package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket rate limiter, one bucket per API key (or per client
 * address when no key is sent). Each bucket refills continuously at
 * {@code requestsPerMinute} tokens per minute and holds at most that
 * many tokens, so a client can burst up to the full minute's budget
 * and then settles to the steady rate. Buckets live in memory, which
 * is the right call for a single instance; a multi-instance
 * deployment would move the buckets to Redis.
 *
 * <p>The clock is injected so tests can move time forward
 * deterministically instead of sleeping.
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final boolean enabled;
    private final int requestsPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.enabled = properties.rateLimitEnabled();
        this.requestsPerMinute = properties.rateLimitRequestsPerMinute();
    }

    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    /** The configured per-minute budget, exposed for response headers. */
    public int limit() {
        return requestsPerMinute;
    }

    public Decision tryConsume(String key) {
        if (!enabled) {
            return new Decision(true, requestsPerMinute, 0);
        }
        if (buckets.size() > 10_000) {
            evictIdleBuckets();
        }
        Bucket bucket = buckets.computeIfAbsent(key,
                k -> new Bucket(requestsPerMinute, clock.millis()));
        return bucket.tryConsume(clock.millis());
    }

    private void evictIdleBuckets() {
        long cutoff = clock.millis() - 10 * 60 * 1000L;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastRefillMillis() < cutoff);
    }

    /** One bucket. All state is guarded by synchronising on the bucket. */
    static final class Bucket {

        private final double capacity;
        private final double refillPerMilli;
        private double tokens;
        private long lastRefillMillis;

        Bucket(int requestsPerMinute, long nowMillis) {
            this.capacity = requestsPerMinute;
            this.refillPerMilli = requestsPerMinute / 60_000.0;
            this.tokens = requestsPerMinute;
            this.lastRefillMillis = nowMillis;
        }

        synchronized Decision tryConsume(long nowMillis) {
            refill(nowMillis);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, (int) Math.floor(tokens), 0);
            }
            double deficit = 1.0 - tokens;
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(deficit / refillPerMilli / 1000.0));
            return new Decision(false, 0, retryAfterSeconds);
        }

        private void refill(long nowMillis) {
            if (nowMillis <= lastRefillMillis) {
                return;
            }
            double added = (nowMillis - lastRefillMillis) * refillPerMilli;
            tokens = Math.min(capacity, tokens + added);
            lastRefillMillis = nowMillis;
        }

        synchronized long lastRefillMillis() {
            return lastRefillMillis;
        }
    }
}
