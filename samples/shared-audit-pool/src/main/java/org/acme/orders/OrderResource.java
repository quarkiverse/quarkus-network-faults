package org.acme.orders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/orders")
public class OrderResource {

    private final DataSource dataSource;
    private final AuditLog audit;

    public OrderResource(DataSource dataSource, AuditLog audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @POST
    public Response place(@QueryParam("item") String item) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO orders (item) VALUES (?)")) {
            insert.setString(1, item);
            insert.executeUpdate();
        } catch (SQLException e) {
            return unavailable();
        }
        audit.record("order placed: " + item);
        return Response.status(Response.Status.CREATED).build();
    }

    @GET
    @Path("/audit")
    public Response auditCount() {
        try {
            return Response.ok(audit.count() + " audit entries").build();
        } catch (SQLException e) {
            return unavailable();
        }
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
    }
}
