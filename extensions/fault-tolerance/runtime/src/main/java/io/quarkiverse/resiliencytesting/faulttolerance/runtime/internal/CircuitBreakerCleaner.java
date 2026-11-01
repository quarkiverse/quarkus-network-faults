package io.quarkiverse.resiliencytesting.faulttolerance.runtime.internal;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.spi.ConsequenceCleaner;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;

/**
 * Resets every circuit breaker when a fault ends, so a breaker the fault opened does not outlive it.
 */
@Singleton
public final class CircuitBreakerCleaner implements ConsequenceCleaner {

    private final CircuitBreakerMaintenance circuitBreakers;

    public CircuitBreakerCleaner(CircuitBreakerMaintenance circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public void cleanUp() {
        circuitBreakers.resetAll();
    }
}
