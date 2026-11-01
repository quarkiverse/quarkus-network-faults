package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class PaymentService {

    private final PaymentClient payments;

    public PaymentService(@RestClient PaymentClient payments) {
        this.payments = payments;
    }

    /**
     * Charges an order, retrying when the call fails. The idempotency key comes from the caller, so every attempt
     * carries the same one, and the payment service can tell a retry from a second payment.
     */
    @Retry(maxRetries = 2, delay = 200)
    public String charge(String idempotencyKey, String orderId) {
        return payments.charge(idempotencyKey, orderId);
    }
}
