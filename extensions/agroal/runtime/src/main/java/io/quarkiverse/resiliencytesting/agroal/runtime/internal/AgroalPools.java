package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.spi.Exhaustion;
import io.quarkiverse.resiliencytesting.spi.PoolResolver;
import io.quarkiverse.resiliencytesting.spi.ResourceTarget;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.agroal.runtime.AgroalDataSourceUtil;

/**
 * The Agroal pool behind a {@code datasource:<name>:jdbc} target.
 */
@Singleton
public final class AgroalPools implements PoolResolver {

    @Override
    public Optional<ResourceTarget> resolve(TargetName target) {
        return DatasourceTargets.datasourceOf(target, false).map(name -> new Pool(target.toString(), name));
    }

    private record Pool(String name, String dataSourceName) implements ResourceTarget {

        @Override
        public Exhaustion exhaust() {
            AgroalDataSource dataSource = AgroalDataSourceUtil.dataSourceIfActive(dataSourceName)
                    .orElseThrow(() -> new IllegalStateException("Datasource '" + dataSourceName + "' is not active"));
            return PoolExhaustion.take(name, dataSource);
        }
    }
}
