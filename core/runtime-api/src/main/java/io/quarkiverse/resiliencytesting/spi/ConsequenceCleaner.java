package io.quarkiverse.resiliencytesting.spi;

/**
 * Undoes what a fault leaves behind in the application once it ends: pooled connections flagged as broken, circuit
 * breakers left open. The extension that owns the state provides one as a CDI bean, which lets it inject that
 * state; every cleaner bean runs when a fault ends and on {@code Faults.resetAll()}.
 */
public interface ConsequenceCleaner {

    void cleanUp();
}
