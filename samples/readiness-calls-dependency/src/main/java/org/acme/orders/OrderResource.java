package org.acme.orders;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Placing an order needs the inventory; listing the orders placed so far does not. With the inventory down the
 * first is a 503 and the second still works, if anything routes a request here.
 */
@Path("/orders")
public class OrderResource {

    private final InventoryClient inventory;
    private final List<String> placed = new CopyOnWriteArrayList<>();

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
        placed.add(item);
        return Response.status(Response.Status.CREATED).build();
    }

    @GET
    public String list() {
        return placed.size() + " orders";
    }
}
