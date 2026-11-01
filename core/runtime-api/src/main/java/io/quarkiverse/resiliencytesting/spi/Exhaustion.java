package io.quarkiverse.resiliencytesting.spi;

/**
 * A resource held in full by the extension, from {@link ResourceTarget#exhaust()}.
 */
public interface Exhaustion {

    /**
     * Gives the resource back to the application. Must not throw: the failure would surface from the end of the fault
     * and fail the test for something the code under test did not do.
     */
    void release();

    /**
     * Whether anything could watch for waiters while the resource was held. A resource whose waiters cannot be
     * observed has no guard: {@link #contended()} is not consulted, and the fault is reported as not judged.
     */
    boolean watched();

    /**
     * Whether anything in the application had to wait for the resource while it was held, which is what the no-effect
     * guard checks. Meaningful once {@link #release()} has returned, and only if {@link #watched()}.
     */
    boolean contended();
}
