package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import java.util.Optional;

import javax.sql.DataSource;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.datasource.common.runtime.DataSourceUtil;

public final class AgroalTargets {

    private AgroalTargets() {
    }

    /**
     * The descriptor for one datasource Agroal reported at build time.
     */
    public static TargetDescriptor descriptor(String name, boolean isDefault) {
        String property = isDefault ? "quarkus.datasource.jdbc.url"
                : "quarkus.datasource." + TargetDescriptor.propertySegment(name) + ".jdbc.url";
        return new TargetDescriptor(property, JdbcUrlRewriter.class,
                DatasourceTargets.jdbc(isDefault ? DataSourceUtil.DEFAULT_DATASOURCE_NAME : name));
    }

    /**
     * A {@code DataSource} bean is the JDBC connection of the datasource its {@code @DataSource} qualifier names.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (!bean.hasType(DataSource.class)) {
                return Optional.empty();
            }
            Optional<io.quarkus.agroal.DataSource> qualifier = bean.qualifier(io.quarkus.agroal.DataSource.class);
            String name = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : DataSourceUtil.DEFAULT_DATASOURCE_NAME;
            return Optional.of(DatasourceTargets.jdbc(name));
        }
    }
}
