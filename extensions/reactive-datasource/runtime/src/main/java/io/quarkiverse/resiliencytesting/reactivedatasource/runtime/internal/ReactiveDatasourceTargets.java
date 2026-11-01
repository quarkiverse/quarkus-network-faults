package io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.reactive.datasource.ReactiveDataSource;

/**
 * The reactive connection of every configured datasource, named {@code datasource:<name>:reactive}.
 */
public final class ReactiveDatasourceTargets {

    private ReactiveDatasourceTargets() {
    }

    /**
     * The descriptor for one datasource a reactive client reported at build time.
     */
    public static TargetDescriptor descriptor(String name, boolean isDefault) {
        String property = isDefault ? "quarkus.datasource.reactive.url"
                : "quarkus.datasource." + TargetDescriptor.propertySegment(name) + ".reactive.url";
        return new TargetDescriptor(property, ReactiveUrlRewriter.class,
                DatasourceTargets.reactive(isDefault ? DataSourceUtil.DEFAULT_DATASOURCE_NAME : name));
    }

    /**
     * A reactive {@code Pool} bean, Vert.x or Mutiny flavoured, named by its {@code @ReactiveDataSource} qualifier.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (!bean.hasType(io.vertx.sqlclient.Pool.class) && !bean.hasType(io.vertx.mutiny.sqlclient.Pool.class)) {
                return Optional.empty();
            }
            Optional<ReactiveDataSource> qualifier = bean.qualifier(ReactiveDataSource.class);
            String name = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : DataSourceUtil.DEFAULT_DATASOURCE_NAME;
            return Optional.of(DatasourceTargets.reactive(name));
        }
    }
}
