package org.acme.orders;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: the pool the audit log writes to is the pool the orders use. When the audit log has used it up, no
 * order can be placed.
 */
@QuarkusTest
class AuditTakesOrdersDownTest {

    @Inject
    Faults faults;

    @Test
    void aFullAuditPoolStopsTheOrders() {
        // the audit log writes through the default datasource: this is its pool
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders/audit").then().statusCode(503);
            given().queryParam("item", "apple").post("/orders").then().statusCode(503);
        }

        given().queryParam("item", "apple").post("/orders").then().statusCode(201);
    }
}
