package io.quarkiverse.resiliencytesting.hr;

import java.time.Duration;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.hibernate.reactive.mutiny.Mutiny;

import io.smallrye.mutiny.Uni;

/**
 * Fruits through the Mutiny session factory; a failure or a wait longer than two seconds is a 503.
 */
@Path("/fruits")
public class FruitResource {

    private final Mutiny.SessionFactory sessions;

    public FruitResource(Mutiny.SessionFactory sessions) {
        this.sessions = sessions;
    }

    @POST
    public Uni<Response> create(@QueryParam("name") String name) {
        Fruit fruit = new Fruit();
        fruit.name = name;
        return guarded(sessions.withTransaction(session -> session.persist(fruit)).replaceWith("created"));
    }

    @GET
    @Path("/count")
    public Uni<Response> count() {
        return guarded(sessions.withSession(
                session -> session.createQuery("select count(f) from Fruit f", Long.class).getSingleResult())
                .map(String::valueOf));
    }

    private static Uni<Response> guarded(Uni<String> result) {
        return result.map(body -> Response.ok(body).build())
                .ifNoItem().after(Duration.ofSeconds(2)).fail()
                .onFailure().recoverWithItem(t -> Response.status(503).entity(String.valueOf(t)).build());
    }
}
