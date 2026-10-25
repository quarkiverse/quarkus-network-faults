package org.acme.orders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/summary")
public class SummaryResource {

    private final Summaries summaries;

    public SummaryResource(Summaries summaries) {
        this.summaries = summaries;
    }

    @GET
    public Response summary(@QueryParam("text") String text) {
        String summary = summaries.summarize(text);
        if (summary == null) {
            return Response.status(503).header("Retry-After", 60).entity("summary unavailable, try again later").build();
        }
        return Response.ok(summary).build();
    }
}
