package org.acme.shop;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/catalog")
public class CatalogResource {

    // The real work of the application: blocking, on a worker thread.
    @GET
    public String list() {
        return "apples, pears";
    }
}
