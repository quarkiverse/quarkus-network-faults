package org.acme.orders;

import java.time.Duration;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.mutiny.Multi;

/**
 * A stand-in for the inventory service, served by this very application so that the sample needs nothing else
 * running. The REST Client reaches it through the Network faults proxy, as it would reach a real service.
 */
@Path("/inventory")
public class InventoryStub {

    // how many stock updates one stream sends, and how far apart: enough to cut the stream after the third event
    private static final int STOCK_UPDATES = 6;
    private static final Duration STOCK_UPDATE_INTERVAL = Duration.ofMillis(300);

    @POST
    @Path("/reserve")
    public Reservation reserve(String item) {
        return Reservation.confirmed(item);
    }

    @GET
    @Path("/stock-updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stockUpdates() {
        return Multi.createFrom().ticks().every(STOCK_UPDATE_INTERVAL)
                .select().first(STOCK_UPDATES)
                .map(tick -> "stock level " + (STOCK_UPDATES - tick));
    }

    @GET
    @Path("/prices/{item}")
    public long price(@PathParam("item") String item) {
        return 42;
    }
}
