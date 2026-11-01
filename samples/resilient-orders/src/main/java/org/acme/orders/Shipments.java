package org.acme.orders;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The orders the stand-in carrier has scheduled for shipping, so that tests can check them.
 */
@ApplicationScoped
public class Shipments {

    private final Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    void schedule(long orderId) {
        scheduled.add(orderId);
    }

    public boolean isScheduled(long orderId) {
        return scheduled.contains(orderId);
    }
}
