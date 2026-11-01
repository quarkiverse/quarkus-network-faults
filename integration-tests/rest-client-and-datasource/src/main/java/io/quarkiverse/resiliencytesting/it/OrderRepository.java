package io.quarkiverse.resiliencytesting.it;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class OrderRepository {

    private final DataSource dataSource;

    public OrderRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void createSchema(@Observes StartupEvent event) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS orders (id SERIAL PRIMARY KEY, item VARCHAR(100) NOT NULL)");
        }
    }

    @Transactional
    public void insert(String item) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO orders(item) VALUES (?)")) {
            ps.setString(1, item);
            ps.executeUpdate();
        }
    }

    public long count() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM orders")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
