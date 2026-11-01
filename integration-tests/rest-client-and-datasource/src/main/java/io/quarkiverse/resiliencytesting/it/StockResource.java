package io.quarkiverse.resiliencytesting.it;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.mutiny.Uni;

/**
 * Consumes the inventory service's stream of stock updates and answers with everything it received. When the stream
 * fails, the answer says what had arrived before it did, which is what a test of a cut or stalled stream asserts on.
 * <p>
 * The stream carries its own idle timeout, because the REST Client's {@code read-timeout} only bounds the wait for
 * the response headers: Vert.x cancels it once they arrive, so a stream that goes quiet after some events would
 * otherwise hang for as long as the upstream does.
 */
@Path("/stock")
public class StockResource {

    private final InventoryClient inventory;

    public StockResource(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    @GET
    @Path("/updates")
    public Uni<Response> updates() {
        List<String> received = new CopyOnWriteArrayList<>();
        return inventory.stockUpdates()
                .ifNoItem().after(Duration.ofSeconds(3)).fail()
                .onItem().invoke(received::add)
                .collect().asList()
                .map(all -> Response.ok(String.join(",", all)).build())
                .onFailure().recoverWithItem(t -> Response.status(502)
                        .entity(t.getClass().getSimpleName() + " after " + received).build());
    }
}
