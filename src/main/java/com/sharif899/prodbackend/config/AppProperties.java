package com.sharif899.prodbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The application's own settings, bound from {@code app.*} in
 * application.properties. Defaults keep the service runnable with
 * no configuration at all.
 *
 * <p>The settings are grouped (rate-limit, cache) so the property
 * names read as {@code app.rate-limit.requests-per-minute} and
 * {@code app.cache.type}. A flat record would not bind those dotted
 * names: {@code app.rate-limit.requests-per-minute} is two levels,
 * not one kebab-case field name, so the values would silently stay
 * at their defaults. The compact constructor fills in a group that
 * has no properties at all.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        RateLimit rateLimit,
        Cache cache) {

    public AppProperties {
        rateLimit = rateLimit == null ? new RateLimit(true, 100) : rateLimit;
        cache = cache == null ? new Cache("memory") : cache;
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100") int requestsPerMinute) {
    }

    public record Cache(
            @DefaultValue("memory") String type) {
    }

    public boolean rateLimitEnabled() {
        return rateLimit.enabled();
    }

    public int rateLimitRequestsPerMinute() {
        return rateLimit.requestsPerMinute();
    }

    public String cacheType() {
        return cache.type();
    }
}
