package io.quarkiverse.resiliencytesting.graphql;

import java.util.concurrent.ExecutionException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Asks the GraphQL service for the lucky number through each kind of client; a client failure is a 503.
 */
@Path("/lucky")
public class LuckyResource {

    private final LuckyClient typesafe;
    private final DynamicGraphQLClient dynamic;

    public LuckyResource(LuckyClient typesafe, @GraphQLClient("lucky") DynamicGraphQLClient dynamic) {
        this.typesafe = typesafe;
        this.dynamic = dynamic;
    }

    @GET
    @Path("/typesafe")
    public Response typesafe() {
        try {
            return Response.ok(String.valueOf(typesafe.lucky())).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }

    @GET
    @Path("/dynamic")
    public Response dynamic() throws InterruptedException {
        try {
            return Response.ok(String.valueOf(dynamic.executeSync("{ lucky }").getData().get("lucky").asInt())).build();
        } catch (ExecutionException | RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
