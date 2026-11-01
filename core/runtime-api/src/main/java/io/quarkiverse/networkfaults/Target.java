package io.quarkiverse.networkfaults;

/**
 * One outbound dependency of the application, fronted by a proxy. Faults are applied to the proxy and act on
 * everything that goes through it; besides the faults of every {@link Endpoint}, a target takes those that act on
 * connections rather than on requests.
 * <p>
 * A fault on a target has it to itself: applying one while any fault is active on it, on some of its requests or on
 * any of the connections it is made of, throws {@link IllegalStateException}, and so does applying a fault to some of
 * its requests while it has one. A target whose pool is exhausted, through {@link NetworkFaults#pool}, takes no
 * network fault and no {@link #dropConnections()}, which throw {@link IllegalStateException} too.
 */
public interface Target extends Endpoint {

    /**
     * The registry name, {@code family:segment:segment}; a target made of several connections answers with the
     * name that covers them.
     */
    String name();

    /**
     * Connections are refused.
     */
    ActiveFault unavailable();

    /**
     * Connection attempts hang until the client's connect timeout. Linux only: the listener's accept queue is left
     * full, so the kernel drops the SYNs.
     */
    ActiveFault connectTimeout();

    /**
     * Connections stay open but nothing is relayed, until the client's read timeout.
     */
    @Override
    ActiveFault hangs();

    /**
     * Reset every live connection now: pooled connections come back broken. One-shot, not a fault that stays active.
     */
    void dropConnections();

    /**
     * What went through the proxy: live connections, and the connections and HTTP requests seen so far.
     */
    @Override
    TargetTraffic traffic();
}
