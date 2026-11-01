package io.quarkiverse.resiliencytesting;

import java.time.Duration;

/**
 * Faults in the style of Toxiproxy's toxics, for tests that need more control than the faults on {@link Target}.
 */
public interface Advanced {

    enum Direction {
        /**
         * Data from the application to the upstream.
         */
        UPSTREAM,
        /**
         * Data from the upstream to the application.
         */
        DOWNSTREAM,
        BOTH
    }

    /**
     * Delay each buffer by {@code latency} plus a random amount up to {@code jitter}, in the given direction.
     */
    ActiveFault latency(Duration latency, Duration jitter, Direction direction);

    /**
     * Limit throughput to the given number of bytes per second, in the given direction.
     */
    ActiveFault bandwidth(long bytesPerSecond, Direction direction);

    /**
     * Split every buffer into chunks of at most {@code chunkSize} bytes, the first sent at once and each following one
     * {@code delay} after the previous.
     */
    ActiveFault slice(int chunkSize, Duration delay, Direction direction);
}
