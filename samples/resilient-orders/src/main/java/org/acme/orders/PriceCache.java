package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;

/**
 * Prices cached in Redis. The cache is an optimization: when Redis is down, a lookup is a miss and a write is
 * skipped, so the application gets slower instead of failing.
 */
@ApplicationScoped
public class PriceCache {

    private final ValueCommands<String, Long> prices;

    public PriceCache(RedisDataSource redis) {
        this.prices = redis.value(Long.class);
    }

    public Long get(String item) {
        try {
            return prices.get(item);
        } catch (RuntimeException e) {
            return null; // a cache that is down is a cache miss
        }
    }

    public void put(String item, long price) {
        try {
            prices.setex(item, 60, price);
        } catch (RuntimeException e) {
            // the next lookup misses again, that is all
        }
    }
}
