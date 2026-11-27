package com.sharif899.prodbackend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * The read cache for order lookups. The default is an in-process
 * map, so the service works with nothing else running. Set
 * {@code app.cache.type=redis} and point
 * {@code spring.data.redis.host} at a Redis to share the cache
 * between instances; values are stored as JSON, and a missing Redis
 * is the operator's explicit choice, so it fails loudly instead of
 * silently falling back.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(AppProperties properties,
                                     ObjectProvider<RedisConnectionFactory> redisFactory) {
        if ("redis".equalsIgnoreCase(properties.cacheType())) {
            RedisConnectionFactory factory = redisFactory.getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException(
                        "app.cache.type=redis but no RedisConnectionFactory is available");
            }
            RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(10))
                    .disableCachingNullValues()
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new GenericJackson2JsonRedisSerializer()));
            return RedisCacheManager.builder(factory)
                    .cacheDefaults(config)
                    .build();
        }
        return new ConcurrentMapCacheManager("orders");
    }
}
