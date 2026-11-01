package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The test side of the same story: what the Dev UI shows by hand, a test pins for good.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class OrderResourceTest {

    @Inject
    Faults faults;

    @Test
    void inventoryHangsSoTheTimeoutFallbackAnswers() {
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs()) {
            when()
                    .post("/orders?item=apple")
                    .then()
                    .statusCode(503);
        }
        // the fault is over and the breaker was reset: the next order goes through
        when()
                .post("/orders?item=apple")
                .then()
                .statusCode(201);
    }

    @Test
    void aStreamCutAfterThreeEventsEndsEarly() {
        try (ActiveFault fault = faults.target(InventoryClient.class).diesMidRequest(Moment.Http.afterSseEvents(3))) {
            when()
                    .get("/orders/stock-updates")
                    .then()
                    .statusCode(200)
                    .body(containsString("stock level 4"))
                    .body(containsString("stream ended early"))
                    .body(not(containsString("stock level 3")));
        }
    }

    @Test
    void aHungDatabaseFailsOnTheAcquisitionTimeout() {
        try (ActiveFault fault = faults.target(DataSource.class).hangs()) {
            when()
                    .get("/orders")
                    .then()
                    .statusCode(503);
        }
    }

    @Test
    void aTransientFailureIsAbsorbedByTheRetries() {
        try (ActiveFault fault = faults.target(InventoryClient.class).http().forFirst(2).http().respondsWith(503)) {
            when()
                    .post("/orders/retrying?item=pear")
                    .then()
                    .statusCode(201);
        }
    }
}
