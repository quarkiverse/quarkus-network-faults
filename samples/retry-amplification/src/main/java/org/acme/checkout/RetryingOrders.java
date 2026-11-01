package org.acme.checkout;

import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Retry;

import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * The bug: a retry around the database call. It helps with a dropped connection, and it hurts with a full pool:
 * every attempt waits the whole acquisition timeout and asks the pool again, so one client request becomes four
 * callers queueing for a connection, and the client's own retry makes it eight. The pool that was full is now
 * besieged, and it cannot drain.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "checkout.circuit-breaker", stringValue = "true", enableIfMissing = true)
public class RetryingOrders implements Orders {

    private final OrdersRepository repository;

    public RetryingOrders(OrdersRepository repository) {
        this.repository = repository;
    }

    @Override
    @Retry(maxRetries = 3)
    public void place(String item) throws SQLException {
        repository.insert(item);
    }
}
