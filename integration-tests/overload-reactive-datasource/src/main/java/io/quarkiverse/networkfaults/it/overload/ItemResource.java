package io.quarkiverse.networkfaults.it.overload;

import java.util.concurrent.CompletionStage;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.quarkus.reactive.datasource.ReactiveDataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.mutiny.sqlclient.Pool;

@Path("/items")
public class ItemResource {

    @Inject
    Pool pool;

    @Inject
    @ReactiveDataSource("audit")
    Pool audit;

    @Inject
    @ReactiveDataSource("burst")
    Pool burst;

    @Inject
    @ReactiveDataSource("hasty")
    Pool hasty;

    /**
     *
     * The same default pool, through the bare Vert.x API.
     *
     */
    @Inject
    io.vertx.sqlclient.Pool barePool;

    @GET
    public Uni<Response> count() {
        return query(pool);
    }

    @GET
    @Path("/audit")
    public Uni<Response> auditCount() {
        return query(audit);
    }

    @GET
    @Path("/burst")
    public Uni<Response> burstCount() {
        return burst.query("SELECT 1").execute()
                .map(rows -> Response.ok(rows.size()).build())
                // only the pool turning the caller away is a 503; any other failure must surface as a 500
                .onFailure(ConnectionPoolTooBusyException.class).recoverWithItem(ItemResource::unavailable);
    }

    @GET
    @Path("/hasty")
    public Uni<Response> hastyCount() {
        return query(hasty);
    }

    @GET
    @Path("/bare")
    public CompletionStage<Response> bareCount() {
        return barePool.query("SELECT 1").execute()
                .map(rows -> Response.ok(rows.size()).build())
                .recover(e -> Future.succeededFuture(unavailable()))
                .toCompletionStage();
    }

    @GET
    @Path("/static")
    public String noDatabase() {
        return "no database here";
    }

    private static Uni<Response> query(Pool pool) {
        return pool.query("SELECT 1").execute()
                .map(rows -> Response.ok(rows.size()).build())
                .onFailure().recoverWithItem(ItemResource::unavailable);
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
    }
}
