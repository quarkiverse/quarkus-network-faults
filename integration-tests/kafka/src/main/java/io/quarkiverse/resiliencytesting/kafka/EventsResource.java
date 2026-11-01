package io.quarkiverse.resiliencytesting.kafka;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/events")
public class EventsResource {

    private final OrderEvents events;

    public EventsResource(OrderEvents events) {
        this.events = events;
    }

    @POST
    public Response publish(@QueryParam("event") String event) {
        try {
            events.publish(event);
            return Response.status(202).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }

    @GET
    public List<String> received() {
        return events.received();
    }
}
