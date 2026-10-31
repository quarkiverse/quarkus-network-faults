package org.acme.orders;

import java.sql.SQLException;

import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * The fix: a pool with no free connection is a temporary condition, and the client is told so. A 503 with a
 * {@code Retry-After} header tells load balancers to try another instance and clients to come back later; a 500
 * tells them the request is broken, and most retry at once, against the pool that is already full.
 */
@IfBuildProperty(name = "orders.pool-timeout-mapper", stringValue = "true")
public class PoolTimeoutMapper {

    @ServerExceptionMapper
    public Response map(SQLException e) {
        // Agroal's own message for a caller that waited the whole acquisition timeout
        if (e.getMessage() != null && e.getMessage().contains("acquisition timeout")) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
        }
        return Response.serverError().build();
    }
}
