package io.quarkiverse.resiliencytesting.it.overload;

import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.smallrye.common.annotation.NonBlocking;

@Path("/loop")
public class LoopResource {

    @Inject
    DataSource dataSource;

    /**
     *
     * The bug: a non-blocking handler that blocks. Harmless alone, fatal once every loop is doing it.
     *
     */
    @GET
    @Path("/blocking")
    @NonBlocking
    public String blocking() throws InterruptedException {
        Thread.sleep(300);
        return "done";
    }

    @GET
    @Path("/fine")
    @NonBlocking
    public String fine() {
        return "done";
    }

    /**
     *
     * The same bug with a dependency: a non-blocking handler that waits on a JDBC call.
     *
     */
    @GET
    @Path("/db")
    @NonBlocking
    public String db() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("select 1");
        }
        return "done";
    }
}
