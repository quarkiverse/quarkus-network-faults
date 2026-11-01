package io.quarkiverse.resiliencytesting.runtime.internal;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.quarkiverse.resiliencytesting.spi.Exhaustion;

/**
 * Every thread of the worker pool, busy with a task that waits for the fault to end. A blocking request dispatched
 * meanwhile queues behind them, or is rejected when the queue is bounded, as it is when real load has taken every
 * worker.
 * <p>
 * The pool's queue cannot be observed: its statistics are off unless {@code jboss.threads.eqe.statistics} is set,
 * and an unbounded queue, the default, reports no size at all. So this fault has no guard. A blocking request made
 * during the window meets the condition by definition, and a non-blocking one not meeting it is the behaviour such
 * a test asserts.
 */
final class WorkerPoolExhaustion implements Exhaustion {

    /**
     * How long taking the threads may wait for the application to give back the ones it holds.
     */
    private static final Duration SETUP_WAIT = Duration.ofSeconds(10);

    private final CountDownLatch end = new CountDownLatch(1);

    static WorkerPoolExhaustion take(String target, Executor executor, int threads) {
        WorkerPoolExhaustion exhaustion = new WorkerPoolExhaustion();
        CountDownLatch started = new CountDownLatch(threads);
        boolean allStarted = false;
        try {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    started.countDown();
                    exhaustion.await();
                });
            }
            // a task that has started holds a thread; once all have, the pool has none left
            allStarted = started.await(SETUP_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            // the queue is bounded and the application holds threads: nothing of ours was queued
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!allStarted) {
            exhaustion.release();
            throw new IllegalStateException("Could not take all " + threads + " threads of " + target
                    + ": the application holds some of them and did not give them back in time");
        }
        return exhaustion;
    }

    private void await() {
        try {
            end.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void release() {
        end.countDown();
    }

    @Override
    public boolean watched() {
        return false;
    }

    @Override
    public boolean contended() {
        return false;
    }
}
