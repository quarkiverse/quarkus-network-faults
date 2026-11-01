package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.mutiny.Multi;

/**
 * The inventory service this application depends on. Here it is served by {@link InventoryStub} in the same process;
 * the URL comes from {@code quarkus.rest-client.inventory.url}, which is the property the Network faults proxy takes
 * over, so every call below goes through the proxy and can be faulted.
 */
@RegisterRestClient(configKey = "inventory")
@Path("/inventory")
public interface InventoryClient {

    @POST
    @Path("/reserve")
    Reservation reserve(String item);

    @GET
    @Path("/stock-updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    Multi<String> stockUpdates();

    @GET
    @Path("/prices/{item}")
    long price(@PathParam("item") String item);
}
