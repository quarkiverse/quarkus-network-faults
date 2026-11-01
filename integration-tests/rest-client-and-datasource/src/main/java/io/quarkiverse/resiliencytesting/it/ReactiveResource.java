package io.quarkiverse.resiliencytesting.it;

import java.time.Duration;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;

@Path("/reactive")
public class ReactiveResource {

    private final Pool pool;

    public ReactiveResource(Pool pool) {
        this.pool = pool;
    }

    @GET
    @Path("/ping")
    public Uni<Response> ping() {
        return pool.query("SELECT 1").execute()
                .map(rows -> Response.ok("pong").build())
                .ifNoItem().after(Duration.ofSeconds(2)).fail()
                .onFailure().recoverWithItem(t -> Response.status(504).entity(String.valueOf(t)).build());
    }
}
