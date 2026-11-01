package io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal;

import java.time.Duration;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.spi.Exhaustion;
import io.quarkiverse.resiliencytesting.spi.PoolResolver;
import io.quarkiverse.resiliencytesting.spi.ResourceTarget;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.reactive.datasource.runtime.DataSourceReactiveRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.DataSourcesReactiveRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.ReactiveDataSourceUtil;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

/**
 * The reactive pool behind a {@code datasource:<name>:reactive} target, Vert.x or Mutiny flavoured.
 */
@Singleton
public final class ReactivePools implements PoolResolver {

    @Override
    public Optional<ResourceTarget> resolve(TargetName target) {
        return DatasourceTargets.datasourceOf(target, true).map(name -> new ReactivePool(target.toString(), name));
    }

    private record ReactivePool(String name, String dataSourceName) implements ResourceTarget {

        private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.of(PoolOptions.DEFAULT_CONNECTION_TIMEOUT,
                PoolOptions.DEFAULT_CONNECTION_TIMEOUT_TIME_UNIT.toChronoUnit());

        @Override
        public Exhaustion exhaust() {
            Pool pool = ReactiveDataSourceUtil.dataSourceIfActive(dataSourceName)
                    .orElseThrow(() -> new IllegalStateException("Datasource '" + dataSourceName + "' is not active"));
            // the pool says neither how big it is nor whether callers wait for it; its configuration does
            DataSourceReactiveRuntimeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                    .getConfigMapping(DataSourcesReactiveRuntimeConfig.class)
                    .dataSources().get(dataSourceName).reactive();
            // the wait queue is unbounded unless configured; a size of zero turns every caller away at once
            Optional<Duration> callerWait;
            if (config.maxWaitQueueSize().isPresent() && config.maxWaitQueueSize().getAsInt() == 0) {
                callerWait = Optional.empty();
            } else {
                callerWait = Optional.of(config.connectionTimeout().orElse(DEFAULT_CONNECTION_TIMEOUT));
            }
            return PoolExhaustion.take(name, pool, config.maxSize(), callerWait);
        }
    }
}
