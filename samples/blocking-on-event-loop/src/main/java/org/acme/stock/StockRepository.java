package org.acme.stock;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StockRepository {

    private final DataSource dataSource;

    public StockRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The stock level, through JDBC: a blocking call, whatever thread it runs on.
     */
    public long level() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT coalesce(sum(quantity), 0) FROM stock")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
