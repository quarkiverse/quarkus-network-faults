package io.quarkiverse.resiliencytesting.spi;

import java.lang.reflect.Method;
import java.util.Optional;

import io.quarkiverse.resiliencytesting.Faults;

/**
 * Maps a CDI bean the application uses to the target behind it, for
 * {@link Faults#target(Class, java.lang.annotation.Annotation...)}
 * and {@link Faults#target(jakarta.enterprise.inject.Instance)}. The extension that owns the bean provides
 * one as a CDI bean, and it does the recognising: a REST Client interface, a {@code DataSource} with a
 * {@code @DataSource("name")} qualifier, a Redis client with a {@code @RedisClientName}. A resolver that needs
 * nothing is a {@code @Singleton} class; one that needs what the build worked out is made by its extension's
 * recorder with that data.
 */
public interface BeanTargetResolver {

    /**
     * The name of the target behind the bean, or empty if this resolver does not know the bean.
     *
     * @throws IllegalArgumentException if the resolver knows the bean but cannot name its target, for example a
     *         client whose qualifier carries no name; the message says what to pass instead
     */
    Optional<TargetName> resolve(TargetBean bean);

    /**
     * The requests a call to {@code method} on the bean of type {@code type} sends, for
     * {@link Faults#target(io.quarkiverse.resiliencytesting.MethodReference.Method0)} and its overloads; empty
     * if this resolver does not know the type, which is what a resolver whose beans send no HTTP requests answers.
     *
     * @throws IllegalArgumentException if the resolver knows the type but the method sends no request it can
     *         describe; the message says why
     */
    default Optional<TargetRequests> requests(Class<?> type, Method method) {
        return Optional.empty();
    }
}
