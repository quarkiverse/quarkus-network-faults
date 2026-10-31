package org.acme.checkout;

import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * The fix: a circuit breaker around the database call. Each failed request asks the pool once, and after two
 * failures the breaker opens and the next requests fail at once, without asking the pool at all. The pool gets
 * the quiet it needs to drain, and the breaker closes again when connections are back.
 */
@ApplicationScoped
@IfBuildProperty(name = "checkout.circuit-breaker", stringValue = "true")
public class GuardedOrders implements Orders {

    private final OrdersRepository repository;

    public GuardedOrders(OrdersRepository repository) {
        this.repository = repository;
    }

    @Override
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0)
    public void place(String item) throws SQLException {
        repository.insert(item);
    }
}
