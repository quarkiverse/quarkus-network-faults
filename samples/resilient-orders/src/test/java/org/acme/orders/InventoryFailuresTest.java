package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class InventoryFailuresTest {

    @Inject
    Faults faults;

    @Test
    void inventoryServiceHangs() {
        Target inventory = faults.target(InventoryClient.class);
        try (var ignored = inventory.hangs()) {
            // the @Timeout fires, and the fallback accepts the order as pending
            when()
                    .post("/orders")
                    .then()
                    .statusCode(202)
                    .body("status", is("PENDING"));
        }

        // fault over, circuit breaker reset: the next order is confirmed right away
        when()
                .post("/orders")
                .then()
                .statusCode(201)
                .body("status", is("CONFIRMED"));
    }

    @Test
    void inventoryServiceFailsTwiceThenRecovers() {
        try (var ignored = faults.target(InventoryClient.class)
                .http().forFirst(2)
                .http().respondsWith(503)) {
            when()
                    .post("/orders/retrying")
                    .then()
                    .statusCode(201);
        }
    }

    @Test
    void circuitBreakerStopsTheCalls() {
        Target inventory = faults.target(InventoryClient.class);
        long before = inventory.traffic().httpRequestsSeen();
        try (var ignored = inventory.http().respondsWith(503)) {
            for (int i = 0; i < 5; i++) {
                when()
                        .post("/orders")
                        .then()
                        .statusCode(202);
            }
        }
        // two failures opened the breaker: the other three orders never reached the inventory service
        assertEquals(2, inventory.traffic().httpRequestsSeen() - before);
    }

    @Test
    void streamIsCutHalfway() {
        try (var ignored = faults.target(InventoryClient.class)
                .diesMidRequest(Moment.Http.afterSseEvents(3))) {
            when()
                    .get("/orders/stock-updates")
                    .then()
                    .statusCode(200)
                    .body(containsString("stream ended early"));
        }
    }
}
