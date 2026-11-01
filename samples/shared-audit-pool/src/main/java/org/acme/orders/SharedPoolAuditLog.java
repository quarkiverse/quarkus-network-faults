package org.acme.orders;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * The bug: the audit log writes through the same datasource as the orders. An audit table that is slow, locked
 * or being reindexed holds connections for longer, and once it holds all of them, orders fail too, although an
 * order needs the audit log for nothing.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "orders.audit-pool", stringValue = "own", enableIfMissing = true)
public class SharedPoolAuditLog extends JdbcAuditLog {

    private final DataSource dataSource;

    public SharedPoolAuditLog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
