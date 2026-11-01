package io.quarkiverse.resiliencytesting.it.overload;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

@Path("/orders")
public class OrderResource {

    @Inject
    DataSource dataSource;

    @Inject
    @io.quarkus.agroal.DataSource("audit")
    DataSource audit;

    @Inject
    GuardedOrders guardedOrders;

    @GET
    public Response count() {
        return query(dataSource);
    }

    @GET
    @Path("/transactional")
    @Transactional
    public Response countInTransaction() {
        return query(dataSource);
    }

    @GET
    @Path("/audit")
    public Response auditCount() {
        return query(audit);
    }

    @GET
    @Path("/guarded")
    public Response guardedCount() {
        try {
            return Response.ok(guardedOrders.count()).build();
        } catch (CircuitBreakerOpenException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("X-Reason", "circuit-open").build();
        } catch (SQLException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("X-Reason", "no-connection").build();
        }
    }

    @GET
    @Path("/static")
    public String noDatabase() {
        return "no database here";
    }

    private static Response query(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 42")) {
            rs.next();
            return Response.ok(rs.getInt(1)).build();
        } catch (SQLException e) {
            // the pool had no connection for us: tell the caller to come back later instead of failing with a 500
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
        }
    }
}
