package org.acme.orders;

import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The bug: the inventory is down, the readiness check says so, and the pod leaves the rotation. It could still list
 * orders, and it could still tell a caller that placing one has to wait, but nothing routes to it any more; and
 * since every pod asks the same inventory, nothing routes anywhere.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
class InventoryOutageTakesThePodOutTest {

    @Inject
    Faults faults;

    @Test
    void thePodLeavesTheRotationWithTheInventory() {
        try (ActiveFault fault = faults.target(InventoryClient.class).unavailable()) {
            // the pod reports itself unready, so the balancer stops sending it anything
            when()
                    .get("/q/health/ready")
                    .then()
                    .statusCode(503);

            // although it could still serve this
            when()
                    .get("/orders")
                    .then()
                    .statusCode(200);
        }

        when()
                .get("/q/health/ready")
                .then()
                .statusCode(200);
    }
}
