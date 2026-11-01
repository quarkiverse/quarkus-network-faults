package io.quarkiverse.resiliencytesting.datahibernate.runtime.internal;

import java.util.Map;
import java.util.Optional;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetName;

/**
 * Quarkus Data and Jakarta Data repositories, resolved to the datasource behind their persistence unit. This
 * module declares no target of its own: a repository is a way to reach a datasource the Agroal or reactive
 * datasource module already proxies, so the resolver answers with that target's name. Which datasource is decided
 * at build time, from the persistence unit build items, and handed to the resolver.
 */
public final class DataRepositoryTargets {

    private DataRepositoryTargets() {
    }

    public static final class Resolver implements BeanTargetResolver {

        // repository class name to the target of its datasource
        private final Map<String, TargetName> repositories;

        public Resolver(Map<String, TargetName> repositories) {
            this.repositories = Map.copyOf(repositories);
        }

        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            for (Class<?> type : bean.rawTypes()) {
                TargetName target = repositories.get(type.getName());
                if (target != null) {
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        }
    }
}
