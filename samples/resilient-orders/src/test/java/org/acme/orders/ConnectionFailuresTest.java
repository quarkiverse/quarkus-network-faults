package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.lessThan;

import java.time.Duration;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class ConnectionFailuresTest {

    @Inject
    Faults faults;

    @Test
    void slowIsWorseThanDown() {
        Target inventory = faults.target(InventoryClient.class);
        try (var callers = Executors.newFixedThreadPool(2);
                var ignored = inventory.hangs(Moment.onNextWrite())) {
            for (int i = 0; i < 2; i++) {
                callers.submit(() -> when().post("/orders"));
            }
            inventory.traffic()
                    .awaitHeldConnections(2, Duration.ofSeconds(5));

            when()
                    .post("/orders")
                    .then()
                    .statusCode(202)
                    .time(lessThan(500L));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void inventoryServiceIsBlackHoled() {
        Target inventory = faults.target(InventoryClient.class);
        inventory.dropConnections();
        try (var ignored = inventory.connectTimeout()) {
            when()
                    .get("/prices/pear")
                    .then()
                    .statusCode(503)
                    .time(lessThan(2000L));
        }
    }
}
