package io.quarkiverse.resiliencytesting.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(baseUri = "http://127.0.0.1:" + PricingClient.PORT)
@Path("/pricing")
public interface PricingClient {

    int PORT = 19876;

    @GET
    String price();
}
