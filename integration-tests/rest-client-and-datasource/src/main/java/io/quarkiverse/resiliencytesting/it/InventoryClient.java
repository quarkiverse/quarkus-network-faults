package io.quarkiverse.resiliencytesting.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.mutiny.Multi;

@RegisterRestClient(configKey = "inventory")
@Path("/inventory")
public interface InventoryClient {

    @POST
    @Path("/reserve")
    String reserve(String item);

    /**
     * A Server-Sent Events stream of stock levels.
     */
    @GET
    @Path("/stock-updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    Multi<String> stockUpdates();
}
