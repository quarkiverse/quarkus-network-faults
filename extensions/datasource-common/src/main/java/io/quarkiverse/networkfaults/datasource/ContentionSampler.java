package io.quarkiverse.networkfaults.datasource;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Watches whether anything waits for an exhausted pool. Neither Agroal nor the Vert.x SQL client offers a hook for a
 * caller starting to wait, so a thread samples the pool's count of waiting callers while the pool is held. A caller
 * waits for the pool's whole timeout, and as long as that is many sampling periods none goes unseen; a pool whose
 * callers wait less than {@link #SHORTEST_WATCHED_WAIT} cannot be watched, see {@link #canWatch(Duration)}.
 */
public final class ContentionSampler {

    private static final long SAMPLING_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    /**
     * Ten samples per wait: enough for a sampler that oversleeps on a busy machine to still see every caller.
     */
    public static final Duration SHORTEST_WATCHED_WAIT = Duration.ofNanos(10 * SAMPLING_PERIOD_NANOS);

    /**
     * How many callers are waiting for the pool right now.
     */
    public interface WaiterCount {
        long now();
    }

    private final WaiterCount waiters;
    private final Thread sampler;
    private volatile boolean released;
    private volatile boolean contended;

    public ContentionSampler(String target, WaiterCount waiters) {
        this.waiters = waiters;
        this.sampler = Thread.ofPlatform().daemon().name("network-faults sampler " + target).unstarted(this::sample);
    }

    /**
     * Whether a caller that waits this long is seen: a zero wait is unbounded and always seen, and a wait shorter
     * than {@link #SHORTEST_WATCHED_WAIT} can come and go between two samples.
     */
    public static boolean canWatch(Duration wait) {
        return wait.isZero() || wait.compareTo(SHORTEST_WATCHED_WAIT) >= 0;
    }

    public void start() {
        sampler.start();
    }

    /**
     * Stops sampling and waits for the last sample, so that {@link #contended()} is final once this returns.
     */
    public void stop() {
        released = true;
        if (sampler.isAlive()) {
            try {
                sampler.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether a caller was seen waiting; meaningful once {@link #stop()} has returned.
     */
    public boolean contended() {
        return contended;
    }

    private void sample() {
        while (!released) {
            if (waiters.now() > 0) {
                contended = true;
            }
            LockSupport.parkNanos(SAMPLING_PERIOD_NANOS);
        }
    }
}
