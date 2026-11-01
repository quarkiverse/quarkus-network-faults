package io.quarkiverse.resiliencytesting.redis;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;

/**
 * Counters kept in Redis through the blocking data source; a Redis failure is a 503 with the cause.
 */
@Path("/counters")
public class CounterResource {

    private final ValueCommands<String, Long> values;

    public CounterResource(RedisDataSource redis) {
        this.values = redis.value(Long.class);
    }

    @POST
    @Path("/{name}")
    public Response increment(@PathParam("name") String name) {
        try {
            return Response.ok(String.valueOf(values.incr(name))).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        try {
            Long value = values.get(name);
            return Response.ok(String.valueOf(value == null ? 0 : value)).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
