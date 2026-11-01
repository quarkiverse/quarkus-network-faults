package io.quarkiverse.resiliencytesting.it;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;

/**
 * A second table on the named {@code audit} datasource, so tests can show faults stay with one datasource.
 */
@ApplicationScoped
public class AuditRepository {

    private final DataSource dataSource;

    public AuditRepository(@io.quarkus.agroal.DataSource("audit") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void createSchema(@Observes StartupEvent event) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS audit (id SERIAL PRIMARY KEY)");
        }
    }

    /**
     * The count, after half a second spent in the database: a query that holds its connection long enough for two
     * requests to overlap, which is how a test fills the pool.
     */
    public long countSlowly() throws SQLException {
        return count("SELECT count(*) FROM audit, pg_sleep(0.5)");
    }

    public long count() throws SQLException {
        return count("SELECT count(*) FROM audit");
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
