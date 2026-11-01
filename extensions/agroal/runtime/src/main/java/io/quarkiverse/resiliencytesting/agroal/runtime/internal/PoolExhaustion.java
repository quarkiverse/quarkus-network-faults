package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.quarkiverse.resiliencytesting.datasource.ContentionSampler;
import io.quarkiverse.resiliencytesting.spi.Exhaustion;

/**
 * Every connection of an Agroal pool, checked out and held, so the application's own {@code getConnection()} waits
 * for the acquisition timeout and fails as it does when real load has used the pool up.
 * <p>
 * A {@link ContentionSampler} watches the pool's waiting-thread count while the connections are held. The count is
 * part of the pool metrics, which are switched on for the duration if the application has them off. A pool whose
 * acquisition timeout is too short to watch has no sampler, and no guard.
 */
final class PoolExhaustion implements Exhaustion {

    /**
     * How long taking a connection may wait when the pool's own acquisition timeout does not bound it, which is the
     * case when the application holds a connection and the acquisition timeout is zero.
     */
    private static final Duration SETUP_WAIT = Duration.ofSeconds(10);

    private final AgroalDataSource dataSource;
    private final List<Connection> held = new ArrayList<>();
    // null when the acquisition timeout is too short to watch
    private final ContentionSampler sampler;
    private final boolean metricsSwitchedOn;

    private PoolExhaustion(String target, AgroalDataSource dataSource) {
        this.dataSource = dataSource;
        AgroalDataSourceConfiguration configuration = dataSource.getConfiguration();
        if (ContentionSampler.canWatch(configuration.connectionPoolConfiguration().acquisitionTimeout())) {
            this.sampler = new ContentionSampler(target, new ContentionSampler.WaiterCount() {
                @Override
                public long now() {
                    return dataSource.getMetrics().awaitingCount();
                }
            });
            this.metricsSwitchedOn = !configuration.metricsEnabled();
            if (metricsSwitchedOn) {
                configuration.setMetricsEnabled(true);
            }
        } else {
            this.sampler = null;
            this.metricsSwitchedOn = false;
        }
    }

    static PoolExhaustion take(String target, AgroalDataSource dataSource) {
        return take(target, dataSource, SETUP_WAIT);
    }

    static PoolExhaustion take(String target, AgroalDataSource dataSource, Duration setupWait) {
        PoolExhaustion exhaustion = new PoolExhaustion(target, dataSource);
        int maxSize = dataSource.getConfiguration().connectionPoolConfiguration().maxSize();
        try {
            for (int i = 0; i < maxSize; i++) {
                exhaustion.held.add(acquireOnOwnThread(dataSource, setupWait));
            }
        } catch (SQLException | TimeoutException e) {
            exhaustion.release();
            throw new IllegalStateException("Could not take all " + maxSize + " connections of " + target
                    + ": the application holds some of them and did not give them back in time", e);
        }
        if (exhaustion.sampler != null) {
            exhaustion.sampler.start();
        }
        return exhaustion;
    }

    /**
     * Agroal remembers which thread holds each connection and, with strict multiple acquisition, refuses a second
     * one to the same thread; a thread of its own per connection keeps the pool none the wiser.
     *
     * @throws TimeoutException if the pool had no connection within {@code setupWait}; the acquisition stays queued
     *         for the pool's own timeout, and a connection it receives after that goes straight back
     */
    private static Connection acquireOnOwnThread(AgroalDataSource dataSource, Duration setupWait)
            throws SQLException, TimeoutException {
        CompletableFuture<Connection> acquisition = CompletableFuture.supplyAsync(() -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, Thread.ofPlatform().daemon()::start);
        try {
            return acquisition.get(setupWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw new CompletionException(e.getCause());
        } catch (TimeoutException e) {
            acquisition.thenAccept(PoolExhaustion::giveBack);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    @Override
    public void release() {
        if (sampler != null) {
            sampler.stop();
        }
        for (Connection connection : held) {
            giveBack(connection);
        }
        held.clear();
        if (metricsSwitchedOn) {
            dataSource.getConfiguration().setMetricsEnabled(false);
        }
    }

    private static void giveBack(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // the pool takes the connection back regardless; a broken one is destroyed there
        }
    }

    @Override
    public boolean watched() {
        return sampler != null;
    }

    @Override
    public boolean contended() {
        return sampler != null && sampler.contended();
    }
}
