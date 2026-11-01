package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.resiliencytesting.spi.ConsequenceCleaner;
import io.quarkus.agroal.runtime.AgroalDataSourceUtil;

/**
 * Flushes every Agroal pool when a fault ends, so connections the fault broke are not handed out again.
 */
@Singleton
public final class AgroalCleaner implements ConsequenceCleaner {

    private static final Logger LOG = Logger.getLogger(AgroalCleaner.class);

    private final Instance<AgroalDataSource> dataSources;

    public AgroalCleaner(@Any Instance<AgroalDataSource> dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public void cleanUp() {
        for (Instance.Handle<AgroalDataSource> handle : dataSources.handles()) {
            handle.get().flush(AgroalDataSource.FlushMode.ALL);
            LOG.debugf("network faults: closed every pooled connection of datasource '%s'",
                    AgroalDataSourceUtil.dataSourceName(handle.getBean()));
        }
    }
}
