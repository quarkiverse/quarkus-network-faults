package org.acme.orders;

import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: readiness reports the pod's own state, and the pod stays in the rotation through the inventory outage.
 * Orders that need the inventory are refused with a 503 and a Retry-After, everything else is served, and when
 * the inventory is back, orders go through again without anything having been restarted or rescheduled.
 */
@QuarkusTest
@TestProfile(OwnStateOnlyProfile.class)
@QuarkusTestResource(InventoryServer.class)
class InventoryOutageDegradesThePodTest {

    @Inject
    NetworkFaults faults;

    @Test
    void thePodStaysInTheRotationAndSaysWhatItCannotDo() {
        try (ActiveFault fault = faults.target(InventoryClient.class).unavailable()) {
            when()
                    .get("/q/health/ready")
                    .then()
                    .statusCode(200);

            when()
                    .post("/orders?item=apple")
                    .then()
                    .statusCode(503)
                    .header("Retry-After", "1");

            when()
                    .get("/orders")
                    .then()
                    .statusCode(200);
        }

        when()
                .post("/orders?item=apple")
                .then()
                .statusCode(201);
    }
}
