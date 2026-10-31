package org.acme.orders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.jboss.logging.Logger;

abstract class JdbcAuditLog implements AuditLog {

    private static final Logger LOG = Logger.getLogger(JdbcAuditLog.class);

    protected abstract DataSource dataSource();

    @Override
    public void record(String event) {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO audit_log (event) VALUES (?)")) {
            insert.setString(1, event);
            insert.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("Audit entry lost: %s (%s)", event, e.getMessage());
        }
    }

    @Override
    public long count() throws SQLException {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM audit_log")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
