package org.acme.orders;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * A stand-in for the payment provider, served by this application and reached through the proxy.
 */
@Path("/payments")
public class PaymentStub {

    private final PaymentLedger ledger;

    public PaymentStub(PaymentLedger ledger) {
        this.ledger = ledger;
    }

    @POST
    public String charge(@HeaderParam("Idempotency-Key") String idempotencyKey, String orderId) {
        return ledger.charge(idempotencyKey, orderId);
    }
}
