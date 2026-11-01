package io.quarkiverse.resiliencytesting.it;

import java.sql.SQLException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/audit")
public class AuditResource {

    private final AuditRepository audit;

    public AuditResource(AuditRepository audit) {
        this.audit = audit;
    }

    @GET
    @Path("/count")
    public long count() throws SQLException {
        return audit.count();
    }

    @GET
    @Path("/slow-count")
    public long slowCount() throws SQLException {
        return audit.countSlowly();
    }
}
