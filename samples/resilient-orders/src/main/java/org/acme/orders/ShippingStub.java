package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * A stand-in for the carrier.
 */
@Path("/shipping")
public class ShippingStub {

    private final Shipments shipments;

    public ShippingStub(Shipments shipments) {
        this.shipments = shipments;
    }

    @POST
    @Path("/{orderId}")
    public void schedule(@PathParam("orderId") long orderId) {
        shipments.schedule(orderId);
    }

    @GET
    @Path("/{orderId}/quote")
    public long quote(@PathParam("orderId") long orderId) {
        return 10;
    }
}
