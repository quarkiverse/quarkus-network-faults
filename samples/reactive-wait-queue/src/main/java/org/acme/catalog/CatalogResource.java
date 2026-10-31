package org.acme.catalog;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;

@Path("/catalog")
public class CatalogResource {

    private final Pool pool;

    public CatalogResource(Pool pool) {
        this.pool = pool;
    }

    @GET
    public Uni<Response> count() {
        return pool.query("SELECT count(*) FROM products").execute()
                .map(rows -> Response.ok(rows.iterator().next().getLong(0) + " products").build())
                // no connection to be had: tell the client to come back later
                .onFailure().recoverWithItem(
                        Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build());
    }
}
