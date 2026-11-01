package org.acme.stock;

import java.sql.SQLException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;

/**
 * The fix: {@code @Blocking}. The method runs on a worker thread, where a JDBC call may wait, and the event loop
 * keeps serving its other connections meanwhile.
 */
@Path("/stock")
@IfBuildProperty(name = "stock.blocking", stringValue = "true")
public class BlockingStockResource {

    private final StockRepository repository;

    public BlockingStockResource(StockRepository repository) {
        this.repository = repository;
    }

    @GET
    @Blocking
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
