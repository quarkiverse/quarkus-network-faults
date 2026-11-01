package org.acme.orders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/orders")
public class OrderResource {

    private final DataSource dataSource;

    public OrderResource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GET
    public String count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM orders")) {
            rs.next();
            return rs.getLong(1) + " orders";
        }
    }

    @POST
    public Response place(@QueryParam("item") String item) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO orders (item) VALUES (?)")) {
            insert.setString(1, item);
            insert.executeUpdate();
        }
        return Response.status(Response.Status.CREATED).build();
    }
}
