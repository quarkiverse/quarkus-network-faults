package io.quarkiverse.resiliencytesting.lc4jstores;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * One turn of a conversation; a failure of the model or of the memory store is a 503 with the cause.
 */
@Path("/chat")
public class ChatResource {

    private final Assistant assistant;

    public ChatResource(Assistant assistant) {
        this.assistant = assistant;
    }

    @GET
    @Path("/{session}")
    public Response chat(@PathParam("session") String session, @QueryParam("text") String text) {
        try {
            return Response.ok(assistant.chat(session, text)).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
