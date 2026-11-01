package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/prices")
public class PriceResource {

    private final PriceCache cache;
    private final InventoryClient inventory;

    public PriceResource(PriceCache cache, @RestClient InventoryClient inventory) {
        this.cache = cache;
        this.inventory = inventory;
    }

    @GET
    @Path("/{item}")
    public Response price(@PathParam("item") String item) {
        Long cached = cache.get(item);
        if (cached != null) {
            return Response.ok(cached).build();
        }
        try {
            long price = inventory.price(item);
            cache.put(item, price);
            return Response.ok(price).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity("price unavailable").build();
        }
    }
}
