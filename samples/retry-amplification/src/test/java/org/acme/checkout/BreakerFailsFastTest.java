package org.acme.checkout;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: each failed request asks the pool once, and after two of them the breaker is open and requests fail at
 * once without asking the pool. When the connections are back, so is the application: the circuit breakers the
 * fault opened are reset when the window closes.
 */
@QuarkusTest
@TestProfile(CircuitBreakerProfile.class)
class BreakerFailsFastTest {

    @Inject
    NetworkFaults faults;

    @Inject
    ConnectionAttempts attempts;

    @Test
    void theBreakerOpensAndThePoolIsLeftAlone() {
        attempts.reset();
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            given().queryParam("item", "apple").post("/checkout").then().statusCode(503).header("X-Reason", "no-connection");
            given().queryParam("item", "apple").post("/checkout").then().statusCode(503).header("X-Reason", "no-connection");
            assertEquals(2, attempts.count(), "one attempt per request");

            // two failures: the breaker is open, and this request does not go near the pool
            given().queryParam("item", "apple").post("/checkout").then().statusCode(503).header("X-Reason", "circuit-open");
            assertEquals(2, attempts.count());
        }

        given().queryParam("item", "apple").post("/checkout").then().statusCode(201);
    }
}
