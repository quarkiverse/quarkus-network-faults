package org.acme.stock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

/**
 * Reads the inventory's stream of stock updates to the end and answers with all of them. When the stream fails, the
 * answer is a 502 that says what had arrived before it did.
 */
@Path("/stock")
public class StockResource {

    private final StockUpdates updates;

    public StockResource(StockUpdates updates) {
        this.updates = updates;
    }

    @GET
    public Uni<Response> snapshot() {
        List<String> received = new CopyOnWriteArrayList<>();
        return updates.updates()
                .onItem().invoke(received::add)
                .collect().asList()
                .map(all -> Response.ok(String.join(",", all)).build())
                .onFailure().recoverWithItem(failure -> Response.status(Response.Status.BAD_GATEWAY)
                        .entity(failure.getClass().getSimpleName() + " after " + received).build());
    }
}
