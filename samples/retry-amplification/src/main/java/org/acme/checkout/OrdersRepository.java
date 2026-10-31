package org.acme.checkout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrdersRepository {

    private final DataSource dataSource;
    private final ConnectionAttempts attempts;

    public OrdersRepository(DataSource dataSource, ConnectionAttempts attempts) {
        this.dataSource = dataSource;
        this.attempts = attempts;
    }

    public void insert(String item) throws SQLException {
        attempts.record();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO orders (item) VALUES (?)")) {
            insert.setString(1, item);
            insert.executeUpdate();
        }
    }
}
