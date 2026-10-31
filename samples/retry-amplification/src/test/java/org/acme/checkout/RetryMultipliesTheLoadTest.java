package org.acme.checkout;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: with the pool full, one request asks the pool four times, each time waiting the whole acquisition
 * timeout, before failing. Every request the application receives while the pool is full is four callers queueing
 * for a connection, and the pool cannot drain.
 */
@QuarkusTest
class RetryMultipliesTheLoadTest {

    @Inject
    NetworkFaults faults;

    @Inject
    ConnectionAttempts attempts;

    @Test
    void oneRequestAsksThePoolFourTimes() {
        attempts.reset();
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            given().queryParam("item", "apple").post("/checkout").then().statusCode(503).header("X-Reason", "no-connection");
            assertEquals(4, attempts.count(), "one request, one attempt and three retries");

            // the client retries, as clients do, and the pool is asked four more times
            given().queryParam("item", "apple").post("/checkout").then().statusCode(503).header("X-Reason", "no-connection");
            assertEquals(8, attempts.count());
        }

        given().queryParam("item", "apple").post("/checkout").then().statusCode(201);
    }
}
