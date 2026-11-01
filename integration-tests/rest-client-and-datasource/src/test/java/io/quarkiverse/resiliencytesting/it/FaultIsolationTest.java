package io.quarkiverse.resiliencytesting.it;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.agroal.DataSource.DataSourceLiteral;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * A fault belongs to one target. Two datasources and two REST Clients of the same types: taking one down leaves
 * the other answering, in both directions.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class FaultIsolationTest {

    @Inject
    Faults faults;

    private Target orders() {
        return faults.target(DataSource.class);
    }

    private Target audit() {
        return faults.target(DataSource.class, new DataSourceLiteral("audit"));
    }

    @Test
    public void theDefaultDatasourceDownLeavesTheNamedOneUp() {
        try (ActiveFault f = orders().unavailable()) {
            when()
                    .get("/orders/count")
                    .then()
                    .statusCode(500);

            when()
                    .get("/audit/count")
                    .then()
                    .statusCode(200)
                    .body(is("0"));
        }

        when()
                .get("/orders/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void theNamedDatasourceDownLeavesTheDefaultOneUp() {
        try (ActiveFault f = audit().unavailable()) {
            when()
                    .get("/audit/count")
                    .then()
                    .statusCode(500);

            when()
                    .get("/orders/count")
                    .then()
                    .statusCode(200);
        }

        when()
                .get("/audit/count")
                .then()
                .statusCode(200)
                .body(is("0"));
    }

    @Test
    public void theInventoryClientDownLeavesThePricingClientUp() {
        try (ActiveFault f = faults.target(InventoryClient.class).unavailable()) {
            when()
                    .post("/orders?item=iso")
                    .then()
                    .statusCode(503);

            when()
                    .get("/orders/price")
                    .then()
                    .statusCode(200)
                    .body(is("9.99"));
        }

        when()
                .post("/orders?item=iso")
                .then()
                .statusCode(201);
    }

    @Test
    public void thePricingClientDownLeavesTheInventoryClientUp() {
        try (ActiveFault f = faults.target(PricingClient.class).unavailable()) {
            when()
                    .get("/orders/price")
                    .then()
                    .statusCode(500);

            when()
                    .post("/orders?item=iso2")
                    .then()
                    .statusCode(201);
        }

        when()
                .get("/orders/price")
                .then()
                .statusCode(200);
    }

    @Test
    public void aFaultOnEverythingIsRefusedWhileOneTargetHasAFault() {
        try (ActiveFault f = audit().unavailable()) {
            assertThrows(IllegalStateException.class, () -> faults.all().unavailable());

            // nothing was applied half-way: the default datasource still answers
            when()
                    .get("/orders/count")
                    .then()
                    .statusCode(200);
        }
    }
}
