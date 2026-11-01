package io.quarkiverse.resiliencytesting;

import java.time.Duration;

/**
 * What went through a {@link Target}'s proxy: its requests, and the connections that carried them.
 */
public interface TargetTraffic extends Traffic {

    /**
     * Connections open right now.
     */
    int connections();

    /**
     * Connections accepted since the application started.
     */
    long connectionsSeen();

    /**
     * Waits until at least {@code atLeast} connections are open, for a test that fills a pool from several threads
     * and must know when the pool is full before it sends the request that should not get a connection. Waiting on
     * what the proxy sees, rather than for a fixed time, is what makes such a test repeatable.
     *
     * @throws AssertionError if the connections are not there within {@code timeout}
     */
    void awaitConnections(int atLeast, Duration timeout);

    /**
     * Waits until at least {@code atLeast} connections are held by a fault: under {@code hangs(onNextWrite())} a
     * connection is held once the application writes on it, so for a pool this is the number of pooled connections
     * that are busy with a request the fault stopped. That is what a test that exhausts a pool has to know before it
     * sends the request that should not get a connection; an open connection may still be idle in the pool.
     *
     * @throws AssertionError if the connections are not held within {@code timeout}
     */
    void awaitHeldConnections(int atLeast, Duration timeout);
}
