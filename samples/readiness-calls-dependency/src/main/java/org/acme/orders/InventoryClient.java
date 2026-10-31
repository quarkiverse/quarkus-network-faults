package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "inventory")
@Path("/inventory")
public interface InventoryClient {

    @POST
    @Path("/reserve")
    String reserve(String item);

    @GET
    @Path("/status")
    String status();
}
