package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class DatabaseFailuresTest {

    @Inject
    NetworkFaults faults;

    @Test
    void databaseHangs() {
        try (var ignored = faults.target(Order.Repository.class).hangs()) {
            when()
                    .get("/orders")
                    .then()
                    .statusCode(503);
        }
    }

    @Test
    void databaseRestarts() {
        when().get("/orders").then().statusCode(200);

        // every pooled connection dies, as in a database restart or failover
        faults.target(Order.Repository.class).dropConnections();

        when()
                .get("/orders")
                .then()
                .statusCode(200);
    }

    @Test
    void databaseDiesMidTransaction() {
        int before = orderCount();
        try (var ignored = faults.target(Order.Repository.class)
                .diesMidRequest()) {
            when()
                    .post("/orders")
                    .then()
                    .statusCode(503);
        }
        assertEquals(before, orderCount(), "nothing half-written");

        when()
                .post("/orders")
                .then()
                .statusCode(201);
    }

    private int orderCount() {
        return when().get("/orders").then().statusCode(200).extract().jsonPath().getList("$").size();
    }
}
