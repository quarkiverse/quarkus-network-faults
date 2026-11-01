package io.quarkiverse.resiliencytesting.graphqlclient.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.UrlRewriter;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.typesafe.api.GraphQLClientApi;

/**
 * GraphQL client targets, {@code graphql-client:<key>}. A typesafe client's url may be configured under its
 * {@code configKey}, its class name or its simple name, and the build step names all three the same; a dynamic
 * client's key is the value of its {@code @GraphQLClient} annotation, {@code default} without one.
 */
public final class GraphQLClientTargets {

    private static final String FAMILY = "graphql-client";
    /**
     * Every GraphQL client.
     */
    private static final TargetName ALL_CLIENTS = TargetName.of(FAMILY);

    public static final String DEFAULT_DYNAMIC_CLIENT = "default";

    private GraphQLClientTargets() {
    }

    public static TargetName name(String key) {
        return TargetName.of(FAMILY, key);
    }

    public static TargetName name(Class<?> api) {
        GraphQLClientApi annotation = api.getAnnotation(GraphQLClientApi.class);
        String configKey = annotation == null ? null : annotation.configKey();
        return name(configKey == null || configKey.isBlank() ? api.getSimpleName() : configKey);
    }

    /**
     * The descriptor of the url property of one config key, under the name of the client it belongs to.
     */
    public static TargetDescriptor descriptor(String key, TargetName name) {
        return new TargetDescriptor(property(key), UrlRewriter.class, name);
    }

    public static String property(String key) {
        return "quarkus.smallrye-graphql-client." + TargetDescriptor.propertySegment(key) + ".url";
    }

    /**
     * A bean whose type is an interface annotated with {@code @GraphQLClientApi} is that typesafe client. A
     * {@code DynamicGraphQLClient} is the client named by a {@code @GraphQLClient} passed along with the type, and
     * every GraphQL client when none is: the annotation is not a CDI qualifier, so the bean itself carries no name.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            Optional<Class<?>> api = bean.typeAnnotatedWith(GraphQLClientApi.class);
            if (api.isPresent()) {
                return Optional.of(name(api.get()));
            }
            if (!bean.hasType(DynamicGraphQLClient.class)) {
                return Optional.empty();
            }
            Optional<GraphQLClient> named = bean.qualifier(GraphQLClient.class);
            return Optional.of(named.isPresent() ? name(named.get().value()) : ALL_CLIENTS);
        }
    }
}
