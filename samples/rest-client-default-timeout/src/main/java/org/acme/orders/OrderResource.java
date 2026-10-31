package org.acme.orders;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Placing an order reserves the item with the inventory service first. An inventory that cannot be reached is a
 * 503, and the caller is expected to come back later; how long it takes to get that 503 is up to the client's
 * timeouts, and nothing here sets them.
 */
@Path("/orders")
public class OrderResource {

    private final InventoryClient inventory;

    public OrderResource(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    @POST
    public Response place(@QueryParam("item") String item) {
        try {
            inventory.reserve(item);
        } catch (ProcessingException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
        }
        return Response.status(Response.Status.CREATED).build();
    }
}
