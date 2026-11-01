package io.quarkiverse.resiliencytesting.it.overload;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

@ApplicationScoped
public class GuardedOrders {

    @Inject
    DataSource dataSource;

    // two failures in a row open the breaker, and it stays open far longer than a test lasts
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0, delay = 60_000)
    public int count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 42")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
