package edu.ilkiv.transit.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Налаштування Redis кешу.
 *
 * Кеші:
 *  - "routes"   — список активних маршрутів, TTL 60 сек
 *  - "vehicles" — список онлайн транспорту, TTL 15 сек (оновлюється часто)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_ROUTES   = "routes";
    public static final String CACHE_VEHICLES = "vehicles";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        // Базова конфігурація: JSON серіалізація, null values не кешуємо
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // Різний TTL для різних кешів
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                CACHE_ROUTES,   base.entryTtl(Duration.ofSeconds(60)),
                CACHE_VEHICLES, base.entryTtl(Duration.ofSeconds(15))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(30)))
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}