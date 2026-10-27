package io.quarkiverse.networkfaults.it;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Uploads to the inventory service. A client of its own, so that its read timeout can be long enough for a large
 * upload over a slow network without touching the timeouts the other tests rely on.
 */
@RegisterRestClient(configKey = "export")
@Path("/inventory")
public interface ExportClient {

    /**
     * Streams an export, chunk by chunk, to the inventory service, which answers with the number of bytes it
     * received.
     */
    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    Uni<String> upload(Multi<byte[]> body);
}
