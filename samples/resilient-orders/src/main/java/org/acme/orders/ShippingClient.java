package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The carrier that ships orders, served by {@link ShippingStub} in the same process and reached through the proxy.
 */
@RegisterRestClient(configKey = "shipping")
@Path("/shipping")
public interface ShippingClient {

    @POST
    @Path("/{orderId}")
    void schedule(@PathParam("orderId") long orderId);

    @GET
    @Path("/{orderId}/quote")
    long quote(@PathParam("orderId") long orderId);
}
