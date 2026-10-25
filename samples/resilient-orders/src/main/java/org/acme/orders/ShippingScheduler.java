package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.common.annotation.Blocking;

/**
 * Ships every order that was placed. A call to the carrier that keeps failing does not block the orders behind it:
 * after its retries, the order goes to a dead-letter topic.
 */
@ApplicationScoped
public class ShippingScheduler {

    private final ShippingClient shipping;

    public ShippingScheduler(@RestClient ShippingClient shipping) {
        this.shipping = shipping;
    }

    @Incoming("orders-to-ship")
    @Blocking
    @Retry(maxRetries = 2, delay = 200)
    public void ship(long orderId) {
        shipping.schedule(orderId);
    }
}
