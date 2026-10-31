package org.acme.stock;

import java.sql.SQLException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.quarkus.arc.properties.UnlessBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * The bug: a method that returns a {@code Uni} is non-blocking by convention, so Quarkus runs it on an event loop,
 * and this one makes a JDBC call. While the pool has a free connection the call takes a millisecond and nobody
 * notices. When the pool is full, the call waits the acquisition timeout, on the event loop, and every connection
 * that loop serves waits with it.
 */
@Path("/stock")
@UnlessBuildProperty(name = "stock.blocking", stringValue = "true", enableIfMissing = true)
public class StockResource {

    private final StockRepository repository;

    public StockResource(StockRepository repository) {
        this.repository = repository;
    }

    @GET
    public Uni<Response> level() {
        return Uni.createFrom().item(response());
    }

    // a database that cannot be used is a 503, and the caller is expected to come back later
    private Response response() {
        try {
            return Response.ok(repository.level() + " in stock").build();
        } catch (SQLException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
        }
    }
}
