package org.acme.stock;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: the JDBC call runs on an event loop. With a free connection it is over in a millisecond and the watch
 * sees nothing; with the pool full it waits the acquisition timeout, on the event loop, and the watch sees that.
 */
@QuarkusTest
class JdbcBlocksTheEventLoopTest {

    @Inject
    Faults faults;

    @Test
    void aQuietPoolHidesTheBlock() {
        try (var ignored = faults.eventLoops().watched()) {
            when().get("/stock").then().statusCode(200);
        }
    }

    @Test
    void aFullPoolShowsTheBlock() {
        var watch = faults.eventLoops().watched();
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/stock").then().statusCode(503);
        }

        AssertionError blocked = assertThrows(AssertionError.class, watch::end);
        assertTrue(blocked.getMessage().contains("vert.x-eventloop-thread"), blocked.getMessage());
    }
}
