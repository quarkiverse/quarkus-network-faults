package org.acme.checkout;

import java.sql.SQLException;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

@Path("/checkout")
public class CheckoutResource {

    private final Orders orders;

    public CheckoutResource(Orders orders) {
        this.orders = orders;
    }

    @POST
    public Response checkout(@QueryParam("item") String item) {
        try {
            orders.place(item);
            return Response.status(Response.Status.CREATED).build();
        } catch (CircuitBreakerOpenException e) {
            return unavailable("circuit-open");
        } catch (SQLException e) {
            return unavailable("no-connection");
        }
    }

    private static Response unavailable(String reason) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").header("X-Reason", reason)
                .build();
    }
}
