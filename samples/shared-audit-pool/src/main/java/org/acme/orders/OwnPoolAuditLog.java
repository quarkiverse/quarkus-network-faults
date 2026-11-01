package org.acme.orders;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * The fix: a datasource of its own for the audit log, so a slow audit table can use up its own pool and no other.
 * This is the bulkhead: the failure of one compartment does not flood the next.
 */
@ApplicationScoped
@IfBuildProperty(name = "orders.audit-pool", stringValue = "own")
public class OwnPoolAuditLog extends JdbcAuditLog {

    private final DataSource dataSource;

    public OwnPoolAuditLog(@io.quarkus.agroal.DataSource("audit") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
