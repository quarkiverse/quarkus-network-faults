package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.MutinyEmitter;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class KafkaFailuresTest {

    @Inject
    NetworkFaults faults;

    @Test
    void kafkaIsDown() {
        int before = orderCount();
        try (var ignored = faults.target(MutinyEmitter.class).unavailable()) {
            when()
                    .post("/orders")
                    .then()
                    .statusCode(503);
        }
        assertEquals(before, orderCount(), "no order without its event");

        when()
                .post("/orders")
                .then()
                .statusCode(201);
    }

    private int orderCount() {
        return when().get("/orders").then().statusCode(200).extract().jsonPath().getList("$").size();
    }
}
