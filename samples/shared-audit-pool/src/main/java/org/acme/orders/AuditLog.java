package org.acme.orders;

import java.sql.SQLException;

public interface AuditLog {

    /**
     * Records an event. The audit log is best-effort: an entry that cannot be written is logged and lost.
     */
    void record(String event);

    long count() throws SQLException;
}
