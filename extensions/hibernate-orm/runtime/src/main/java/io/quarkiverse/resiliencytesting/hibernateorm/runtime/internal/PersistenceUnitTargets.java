package io.quarkiverse.resiliencytesting.hibernateorm.runtime.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.hibernate.orm.runtime.PersistenceUnitUtil;

/**
 * Hibernate ORM and Hibernate Reactive persistence units, resolved to the datasource behind them. This module
 * declares no target of its own: a session or session factory is a way to reach a datasource the Agroal or
 * reactive datasource module already proxies, so the resolver answers with that target's name. Which datasource
 * each unit uses is decided at build time and handed to the resolver, once for blocking and once for reactive
 * units, since both may carry the same name.
 */
public final class PersistenceUnitTargets {

    /**
     * The bean types Hibernate ORM registers per unit, as names: the reactive ones must not be loaded here.
     */
    private static final Set<String> BLOCKING_TYPES = Set.of("jakarta.persistence.EntityManagerFactory",
            "jakarta.persistence.EntityManager", "org.hibernate.SessionFactory", "org.hibernate.Session",
            "org.hibernate.StatelessSession");
    private static final Set<String> REACTIVE_TYPES = Set.of("org.hibernate.reactive.mutiny.Mutiny$SessionFactory",
            "org.hibernate.reactive.mutiny.Mutiny$Session", "org.hibernate.reactive.mutiny.Mutiny$StatelessSession");

    private PersistenceUnitTargets() {
    }

    /**
     * An entity manager, session or session factory bean, blocking or Mutiny, is the persistence unit its
     * {@code @PersistenceUnit} qualifier names, the default one without it.
     */
    public static final class Resolver implements BeanTargetResolver {

        // persistence unit name to datasource target name, blocking and reactive units apart
        private final Map<String, TargetName> blockingUnits;
        private final Map<String, TargetName> reactiveUnits;

        public Resolver(Map<String, TargetName> blockingUnits, Map<String, TargetName> reactiveUnits) {
            this.blockingUnits = Map.copyOf(blockingUnits);
            this.reactiveUnits = Map.copyOf(reactiveUnits);
        }

        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            boolean blocking = false;
            boolean reactive = false;
            for (Class<?> type : bean.rawTypes()) {
                blocking |= BLOCKING_TYPES.contains(type.getName());
                reactive |= REACTIVE_TYPES.contains(type.getName());
            }
            if (!blocking && !reactive) {
                return Optional.empty();
            }
            Optional<PersistenceUnit> qualifier = bean.qualifier(PersistenceUnit.class);
            String unit = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : PersistenceUnitUtil.DEFAULT_PERSISTENCE_UNIT_NAME;
            TargetName target = (reactive ? reactiveUnits : blockingUnits).get(unit);
            if (target == null) {
                throw new IllegalArgumentException("No " + (reactive ? "reactive" : "blocking") + " persistence unit '"
                        + unit + "' is known; units: " + (reactive ? reactiveUnits : blockingUnits).keySet());
            }
            return Optional.of(target);
        }
    }
}
