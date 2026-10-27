package io.quarkiverse.networkfaults.it;

import java.util.concurrent.atomic.AtomicLong;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Streams a large export to the inventory service, built chunk by chunk as the REST Client asks for it, and answers
 * with how long building it and uploading it took. With backpressure the two durations are close, since a chunk is
 * only built once the network has taken the previous ones; an application that collected the export into memory
 * before sending it would build it in a few milliseconds and spend the rest of the time sending it.
 */
@Path("/export")
public class ExportResource {

    /**
     * 64 MB in 64 KB chunks: large next to what the proxy and the kernel buffer between the application and the
     * upstream, which is what makes the time the application spends producing the export tell the two apart.
     */
    static final int CHUNK_SIZE = 64 * 1024;
    static final int CHUNKS = 1024;

    private final ExportClient export;

    public ExportResource(@RestClient ExportClient export) {
        this.export = export;
    }

    /**
     * Answers with two durations in milliseconds, {@code "<produced> <uploaded>"}: when the last chunk was handed to
     * the REST Client, and when the inventory service acknowledged the whole upload.
     */
    @POST
    public Uni<String> export() {
        long start = System.nanoTime();
        AtomicLong producedAt = new AtomicLong();
        byte[] chunk = new byte[CHUNK_SIZE];
        Multi<byte[]> body = Multi.createFrom().range(0, CHUNKS)
                .map(i -> chunk)
                .onCompletion().invoke(() -> producedAt.set(System.nanoTime()));
        return export.upload(body)
                .map(received -> millisSince(start, producedAt.get()) + " " + millisSince(start, System.nanoTime()));
    }

    private static long millisSince(long startNanos, long nanos) {
        return (nanos - startNanos) / 1_000_000;
    }
}
