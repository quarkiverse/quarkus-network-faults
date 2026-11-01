package io.quarkiverse.resiliencytesting.grpc;

import java.time.Duration;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.resiliencytesting.grpc.proto.Greeter;
import io.quarkiverse.resiliencytesting.grpc.proto.HelloReply;
import io.quarkiverse.resiliencytesting.grpc.proto.HelloRequest;
import io.quarkus.grpc.GrpcClient;

/**
 * Greets through the gRPC client, waiting at most two seconds for the answer; a failed or overdue call is a 503
 * with the cause.
 */
@Path("/greet")
public class GreetingResource {

    private final Greeter greeter;

    public GreetingResource(@GrpcClient("greeter") Greeter greeter) {
        this.greeter = greeter;
    }

    @GET
    public Response greet(@QueryParam("name") String name) {
        try {
            String message = greeter.sayHello(HelloRequest.newBuilder().setName(name).build())
                    .map(HelloReply::getMessage)
                    .await().atMost(Duration.ofSeconds(2));
            return Response.ok(message).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
