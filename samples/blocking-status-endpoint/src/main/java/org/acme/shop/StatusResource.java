package org.acme.shop;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * The bug: the status endpoint the load balancer and the uptime monitor poll. It returns a constant and does no
 * I/O, but a plain JAX-RS method is dispatched to a worker thread, so it needs a free worker like any other
 * request. When the workers are saturated it goes down with them, and the monitor pages for an instance that is
 * busy, not broken.
 */
@Path("/status")
@UnlessBuildProperty(name = "shop.status-non-blocking", stringValue = "true", enableIfMissing = true)
public class StatusResource {

    @GET
    public String status() {
        return "up";
    }
}
