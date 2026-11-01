package org.acme.reports;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/reports")
public class ReportResource {

    // A blocking endpoint: it runs on a worker thread, and it needs a free one to run at all.
    @GET
    public String render() {
        return "report rendered on " + Thread.currentThread().getName();
    }
}
