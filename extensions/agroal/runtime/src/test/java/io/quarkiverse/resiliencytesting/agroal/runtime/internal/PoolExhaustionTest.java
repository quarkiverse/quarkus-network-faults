package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;

class PoolExhaustionTest {

    private static final String TARGET = "datasource:test:jdbc";

    private AgroalDataSource dataSource;

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void setupGivesUpWhenTheApplicationKeepsAConnectionAndCallersWaitForever() throws Exception {
        dataSource = pool(2, Duration.ZERO);
        try (Connection kept = dataSource.getConnection()) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                var e = assertThrows(IllegalStateException.class,
                        () -> PoolExhaustion.take(TARGET, dataSource, Duration.ofMillis(200)));
                assertTrue(e.getMessage().contains("Could not take all 2 connections"), e.getMessage());
            });
        }
        // the acquisition that gave up is still queued in the pool; once it gets the kept connection, it must go back
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (dataSource.getMetrics().activeCount() > 0) {
                Thread.sleep(10);
            }
        });
    }

    @Test
    void nothingWaitingIsNotContended() {
        dataSource = pool(1, Duration.ofMillis(200));
        PoolExhaustion exhaustion = PoolExhaustion.take(TARGET, dataSource);
        exhaustion.release();
        assertFalse(exhaustion.contended());
    }

    @Test
    void aCallerThatWaitedIsSeen() {
        dataSource = pool(1, Duration.ofMillis(200));
        PoolExhaustion exhaustion = PoolExhaustion.take(TARGET, dataSource);
        assertThrows(SQLException.class, dataSource::getConnection);
        exhaustion.release();
        assertTrue(exhaustion.watched());
        assertTrue(exhaustion.contended());
    }

    @Test
    void aWaitTooShortToWatchIsNotWatched() {
        dataSource = pool(1, Duration.ofNanos(100_000));
        PoolExhaustion exhaustion = PoolExhaustion.take(TARGET, dataSource);
        assertThrows(SQLException.class, dataSource::getConnection);
        exhaustion.release();
        assertFalse(exhaustion.watched());
    }

    @Test
    void releaseGivesEveryConnectionBack() throws Exception {
        dataSource = pool(2, Duration.ofMillis(200));
        PoolExhaustion.take(TARGET, dataSource).release();
        assertEquals(0, dataSource.getMetrics().activeCount());
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            assertEquals(2, dataSource.getMetrics().activeCount());
        }
    }

    private static AgroalDataSource pool(int maxSize, Duration acquisitionTimeout) {
        try {
            // metrics on, so the tests can count the active connections
            return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
                    .metricsEnabled(true)
                    .connectionPoolConfiguration(pool -> pool
                            .maxSize(maxSize)
                            .acquisitionTimeout(acquisitionTimeout)
                            .connectionFactoryConfiguration(factory -> factory
                                    .jdbcUrl("jdbc:h2:mem:exhaustion;DB_CLOSE_DELAY=-1"))));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
