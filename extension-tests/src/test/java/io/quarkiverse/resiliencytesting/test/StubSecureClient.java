package io.quarkiverse.resiliencytesting.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Registered with the REST Client extension at an {@code https} url, so its target is not reached over plaintext
 * HTTP.
 */
@Path("/api")
@RegisterRestClient(configKey = "secure")
public interface StubSecureClient {

    @GET
    String get();
}
