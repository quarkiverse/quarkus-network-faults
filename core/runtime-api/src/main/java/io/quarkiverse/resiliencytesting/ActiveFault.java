package io.quarkiverse.resiliencytesting;

/**
 * A fault that is currently applied to a target. Ending it restores normal relaying and cleans up consequences
 * such as flagged pooled connections and open circuit breakers.
 */
public interface ActiveFault extends AutoCloseable {

    void end();

    @Override
    default void close() {
        end();
    }
}
