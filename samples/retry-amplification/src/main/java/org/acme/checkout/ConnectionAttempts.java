package org.acme.checkout;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * How many times the application asked the pool for a connection: the load one request puts on the database.
 */
@ApplicationScoped
public class ConnectionAttempts {

    private final AtomicInteger count = new AtomicInteger();

    void record() {
        count.incrementAndGet();
    }

    public int count() {
        return count.get();
    }

    public void reset() {
        count.set(0);
    }
}
