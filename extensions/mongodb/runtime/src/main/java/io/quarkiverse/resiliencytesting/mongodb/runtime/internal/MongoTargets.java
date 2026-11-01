package io.quarkiverse.resiliencytesting.mongodb.runtime.internal;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import com.mongodb.client.MongoClient;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.runtime.MongoConfig;

/**
 * The servers of every MongoDB client the application uses, named {@code mongodb:<client name>}; further servers
 * of one client get {@code #2}, {@code #3}. A client's address is either its {@code connection-string} or its
 * {@code hosts} list.
 */
public final class MongoTargets {

    private static final String FAMILY = "mongodb";

    private MongoTargets() {
    }

    /**
     * The descriptors for one client the MongoDB extension reported at build time.
     */
    public static List<TargetDescriptor> descriptors(String name, boolean isDefault) {
        String prefix = isDefault ? "quarkus.mongodb." : "quarkus.mongodb." + TargetDescriptor.propertySegment(name) + ".";
        TargetName target = name(isDefault ? MongoConfig.DEFAULT_CLIENT_NAME : name);
        return List.of(
                new TargetDescriptor(prefix + "connection-string", MongoConnectionStringRewriter.class, target),
                new TargetDescriptor(prefix + "hosts", MongoHostsRewriter.class, target));
    }

    static TargetName name(String client) {
        return TargetName.of(FAMILY, client);
    }

    /**
     * A {@code MongoClient} or {@code ReactiveMongoClient} is the client its {@code @MongoClientName} names.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (!bean.hasType(MongoClient.class) && !bean.hasType(ReactiveMongoClient.class)) {
                return Optional.empty();
            }
            Optional<MongoClientName> qualifier = bean.qualifier(MongoClientName.class);
            String name = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : MongoConfig.DEFAULT_CLIENT_NAME;
            return Optional.of(name(name));
        }
    }
}
