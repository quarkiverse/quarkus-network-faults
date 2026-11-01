package org.acme.orders;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: the audit log has a pool of its own. When it has used it up, the audit log is unavailable and the
 * orders are not: they are placed, and the audit entry is logged as lost.
 */
@QuarkusTest
@TestProfile(OwnAuditPoolProfile.class)
class OrdersSurviveAuditOutageTest {

    private static final io.quarkus.agroal.DataSource AUDIT = new io.quarkus.agroal.DataSource.DataSourceLiteral("audit");

    @Inject
    Faults faults;

    @Test
    void aFullAuditPoolLeavesTheOrdersAlone() {
        try (var ignored = faults.pool(DataSource.class, AUDIT).exhausted()) {
            when().get("/orders/audit").then().statusCode(503);
            given().queryParam("item", "apple").post("/orders").then().statusCode(201);
        }

        when().get("/orders/audit").then().statusCode(200);
    }
}
