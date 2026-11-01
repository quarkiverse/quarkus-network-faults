package io.quarkiverse.resiliencytesting.redis.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.redis.client.RedisClientName;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.runtime.client.config.RedisConfig;

/**
 * The hosts of every Redis client the application requested, named {@code redis:<client name>}; a client with
 * several hosts (cluster, sentinel, replication) gets {@code #2}, {@code #3} for the others.
 */
public final class RedisTargets {

    private static final String FAMILY = "redis";

    private RedisTargets() {
    }

    /**
     * The descriptor for one client the Redis extension reported at build time.
     */
    public static TargetDescriptor descriptor(String name) {
        String property = RedisConfig.DEFAULT_CLIENT_NAME.equals(name) ? "quarkus.redis.hosts"
                : "quarkus.redis." + TargetDescriptor.propertySegment(name) + ".hosts";
        return new TargetDescriptor(property, RedisHostsRewriter.class, name(name));
    }

    static TargetName name(String client) {
        return TargetName.of(FAMILY, client);
    }

    /**
     * A data source, a Vert.x Redis client or a {@code RedisAPI} bean is the client its {@code @RedisClientName}
     * qualifier names, the default one without it.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            boolean redis = bean.hasType(RedisDataSource.class) || bean.hasType(ReactiveRedisDataSource.class)
                    || bean.hasType(io.vertx.mutiny.redis.client.Redis.class) || bean.hasType(io.vertx.redis.client.Redis.class)
                    || bean.hasType(io.vertx.mutiny.redis.client.RedisAPI.class)
                    || bean.hasType(io.vertx.redis.client.RedisAPI.class);
            if (!redis) {
                return Optional.empty();
            }
            Optional<RedisClientName> qualifier = bean.qualifier(RedisClientName.class);
            String name = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : RedisConfig.DEFAULT_CLIENT_NAME;
            return Optional.of(name(name));
        }
    }
}
