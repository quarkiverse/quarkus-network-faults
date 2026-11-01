package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: a read timeout on the client. The same hung inventory, and the caller is told to come back after one
 * second instead of thirty; when the inventory answers again, so does the application.
 */
@QuarkusTest
@TestProfile(ReadTimeoutProfile.class)
@QuarkusTestResource(InventoryServer.class)
class OrdersFailFastTest {

    @Inject
    Faults faults;

    @Test
    void anOrderGivesUpOnTheInventoryAfterOneSecond() {
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/orders?item=apple")
                    .then()
                    .statusCode(503)
                    .header("Retry-After", "1"));
            // the one-second read timeout, with slack for scheduling, and well under the thirty seconds of the default
            assertTrue(ms >= 900 && ms < 3_000, "expected the 1 s read timeout to answer, took " + ms + " ms");
        }

        when()
                .post("/orders?item=apple")
                .then()
                .statusCode(201);
    }
}
