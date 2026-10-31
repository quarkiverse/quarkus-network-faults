package org.acme.shop;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.NonBlocking;

/**
 * The fix: {@code @NonBlocking}. The endpoint runs on the event loop, which the worker pool's saturation does not
 * touch, so the monitor sees what is true: the instance is up.
 */
@Path("/status")
@IfBuildProperty(name = "shop.status-non-blocking", stringValue = "true")
public class NonBlockingStatusResource {

    @GET
    @NonBlocking
    public String status() {
        return "up";
    }
}
