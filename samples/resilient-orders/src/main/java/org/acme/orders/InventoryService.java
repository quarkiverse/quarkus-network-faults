package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The resilience policy around the inventory service: the part of the application the tests verify.
 */
@ApplicationScoped
public class InventoryService {

    private final InventoryClient inventory;

    public InventoryService(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    /**
     * Reserves an item; when the inventory service is too slow, the order is not lost but accepted as pending, and
     * the reservation is retried later.
     */
    @Timeout(2000)
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0)
    @Bulkhead(2)
    @Fallback(fallbackMethod = "reserveLater")
    public Reservation reserve(String item) {
        return inventory.reserve(item);
    }

    Reservation reserveLater(String item) {
        return Reservation.pending(item);
    }

    /**
     * Reserves an item, retrying up to three times: a transient failure is absorbed, one that outlasts the retries is
     * not.
     */
    @Retry(maxRetries = 3, delay = 200)
    public Reservation reserveWithRetry(String item) {
        return inventory.reserve(item);
    }
}
