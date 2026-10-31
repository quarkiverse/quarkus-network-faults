package io.quarkiverse.networkfaults.it.overload;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.smallrye.common.annotation.NonBlocking;

@Path("/work")
public class WorkResource {

    @GET
    public String blocking() {
        return "done on " + Thread.currentThread().getName();
    }

    @GET
    @Path("/status")
    @NonBlocking
    public String nonBlocking() {
        return "up";
    }
}
