package io.quarkiverse.resiliencytesting.it;

import java.sql.SQLException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/orders")
public class OrderResource {

    private final InventoryService inventory;
    private final OrderRepository orders;
    private final FailureLog failures;
    private final PricingClient pricing;

    public OrderResource(InventoryService inventory, OrderRepository orders, FailureLog failures,
            @RestClient PricingClient pricing) {
        this.inventory = inventory;
        this.orders = orders;
        this.failures = failures;
        this.pricing = pricing;
    }

    @GET
    @Path("/price")
    public String price() {
        return pricing.price();
    }

    @POST
    public Response create(@QueryParam("item") String item) throws SQLException {
        if (inventory.reserve(item) == null) {
            return Response.status(503).entity("inventory unavailable").build();
        }
        orders.insert(item);
        return Response.status(201).entity("created").build();
    }

    @POST
    @Path("/retrying")
    public Response createWithRetry(@QueryParam("item") String item) throws SQLException {
        try {
            inventory.reserveWithRetry(item);
        } catch (RuntimeException e) {
            failures.record(e);
            return Response.status(503).entity("inventory unavailable after retries").build();
        }
        orders.insert(item);
        return Response.status(201).entity("created").build();
    }

    @GET
    @Path("/count")
    public long count() throws SQLException {
        return orders.count();
    }

    @GET
    @Path("/last-failure")
    public String lastFailure() {
        return failures.last();
    }

    @POST
    @Path("/last-failure/clear")
    public void clearLastFailure() {
        failures.clear();
    }
}
