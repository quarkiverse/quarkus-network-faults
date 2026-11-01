package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.mutiny.Multi;

/**
 * The inventory service this application depends on. In the demo it is served by {@link InventoryResource} in the same
 * process; the URL comes from {@code quarkus.rest-client.inventory.url}, which is the property the Resiliency Testing
 * proxy takes over, so every call below goes through the proxy and can be faulted.
 */
@RegisterRestClient(configKey = "inventory")
@Path("/inventory")
public interface InventoryClient {

    @POST
    @Path("/reserve")
    String reserve(String item);

    /**
     * A Server-Sent Events stream of stock levels, for the faults that strike after n events.
     */
    @GET
    @Path("/stock-updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    Multi<String> stockUpdates();
}
