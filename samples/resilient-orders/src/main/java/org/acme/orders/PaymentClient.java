package org.acme.orders;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The payment service, served by {@link PaymentStub} in the same process and reached through the proxy.
 */
@RegisterRestClient(configKey = "payments")
@Path("/payments")
public interface PaymentClient {

    @POST
    String charge(@HeaderParam("Idempotency-Key") String idempotencyKey, String orderId);
}
