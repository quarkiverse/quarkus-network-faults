package io.quarkiverse.resiliencytesting.junit;

/**
 * How long a call took, for a test that asserts a timeout fired: the lower bound is the proof that the timeout, and
 * not something quicker, ended the call; an upper bound needs slack for a slow build machine.
 */
public final class Stopwatch {

    private Stopwatch() {
    }

    /**
     * Runs {@code call} and returns how long it took, in milliseconds.
     */
    public static long millis(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
