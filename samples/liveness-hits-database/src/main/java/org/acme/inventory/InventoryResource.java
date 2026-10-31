package org.acme.inventory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/inventory")
public class InventoryResource {

    private final DataSource dataSource;

    public InventoryResource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GET
    public String count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM items")) {
            rs.next();
            return rs.getLong(1) + " items";
        }
    }
}
