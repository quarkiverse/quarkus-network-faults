package io.quarkiverse.networkfaults.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Registered with the REST Client extension, so its {@code quarkus.rest-client.stub.url} becomes a target.
 */
@Path("/api")
@RegisterRestClient(configKey = "stub")
public interface StubClient {

    @GET
    String get();
}
