package io.quarkiverse.networkfaults;

/**
 * Something the application can run out of, from {@link NetworkFaults#pool} or {@link NetworkFaults#workerPool()}:
 * the fault takes all of it and keeps it until it ends, so whatever needs it waits, or fails, as it would under load.
 * Unlike a network fault, this needs no proxy, so it works without {@code NetworkFaultsProfile}.
 */
public interface Exhaustible {

    /**
     * The name of the target this pool belongs to, as {@link Target#name()} gives it, or {@code worker-pool}.
     */
    String name();

    /**
     * Takes all of the resource and holds it until the fault ends. Nothing waiting for it while it was held fails the
     * test: the fault was on something the code under test does not use.
     *
     * @throws IllegalStateException if it is already exhausted, the target it belongs to has an active network fault,
     *         or the resource could not be taken whole
     */
    ActiveFault exhausted();
}
