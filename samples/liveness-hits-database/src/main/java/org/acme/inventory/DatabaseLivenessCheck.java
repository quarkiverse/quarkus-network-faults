package org.acme.inventory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * The bug: a liveness check that needs a database connection. Liveness answers "is this process healthy, or
 * should it be restarted?", and a process whose pool is full is healthy and busy. Restarting it drops every
 * request in flight and starts a new process against the same full database, which fails the same check, and
 * so on across the whole deployment.
 * <p>
 * Quarkus already reports the datasource in the readiness check, where it belongs: a pod that cannot reach its
 * database is taken out of rotation until it can, and not restarted.
 */
@Liveness
@ApplicationScoped
@UnlessBuildProperty(name = "inventory.database-liveness", stringValue = "false", enableIfMissing = true)
public class DatabaseLivenessCheck implements HealthCheck {

    private final DataSource dataSource;

    public DatabaseLivenessCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public HealthCheckResponse call() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return HealthCheckResponse.up("database");
        } catch (SQLException e) {
            return HealthCheckResponse.down("database");
        }
    }
}
