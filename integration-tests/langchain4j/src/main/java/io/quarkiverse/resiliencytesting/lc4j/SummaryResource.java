package io.quarkiverse.resiliencytesting.lc4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

@Path("/summarize")
public class SummaryResource {

    private final Summaries summaries;
    private final OpenAiStreamingSummarizer streaming;

    public SummaryResource(Summaries summaries, OpenAiStreamingSummarizer streaming) {
        this.summaries = summaries;
        this.streaming = streaming;
    }

    /**
     * The streamed summary, assembled once the stream ends; when it fails, what had arrived before it did. The
     * stream carries its own idle timeout: the client's {@code quarkus.langchain4j.timeout} is a REST Client
     * read-timeout, which Vert.x cancels once the response headers arrive.
     */
    @GET
    @Path("/stream/openai")
    public Uni<Response> stream(@QueryParam("text") String text) {
        List<String> tokens = new CopyOnWriteArrayList<>();
        return streaming.summarize(text)
                .ifNoItem().after(Duration.ofSeconds(3)).fail()
                .onItem().invoke(tokens::add)
                .collect().asList()
                .map(all -> Response.ok(String.join("", all)).build())
                .onFailure().recoverWithItem(t -> Response.status(502)
                        .entity(t.getClass().getSimpleName() + " after " + tokens).build());
    }

    @GET
    @Path("/{provider}")
    public Response summarize(@PathParam("provider") String provider, @QueryParam("text") String text) {
        String summary = summaries.summarize(provider, text);
        if (summary == null) {
            return Response.status(503).entity("model unavailable").build();
        }
        return Response.ok(summary).build();
    }

    @GET
    @Path("/last-failure")
    public String lastFailure() {
        return summaries.lastFailure();
    }

    @POST
    @Path("/last-failure/clear")
    public void clear() {
        summaries.clearLastFailure();
    }
}
