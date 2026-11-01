package io.quarkiverse.networkfaults.agroal.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.networkfaults.datasource.DatasourceTargets;
import io.quarkiverse.networkfaults.spi.Exhaustion;
import io.quarkiverse.networkfaults.spi.PoolResolver;
import io.quarkiverse.networkfaults.spi.ResourceTarget;
import io.quarkiverse.networkfaults.spi.TargetName;
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
