package io.quarkiverse.resiliencytesting.spi;

import java.util.Optional;

/**
 * Maps a target to the pool behind it, for {@link io.quarkiverse.resiliencytesting.Faults#pool}. The extension
 * that owns the pool provides one as a CDI bean, with or without fault injection on, and so must the
 * {@link BeanTargetResolver} that names the pool's bean: a pool is exhausted in tests that have no proxies.
 */
public interface PoolResolver {

    /**
     * The pool behind the target, or empty if this resolver does not know the target.
     */
    Optional<ResourceTarget> resolve(TargetName target);
}
