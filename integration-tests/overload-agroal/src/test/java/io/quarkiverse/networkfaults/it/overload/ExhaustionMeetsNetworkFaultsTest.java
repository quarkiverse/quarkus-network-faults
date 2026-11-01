package io.quarkiverse.networkfaults.it.overload;

import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.agroal.DataSource.DataSourceLiteral;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class ExhaustionMeetsNetworkFaultsTest {

    @Inject
    NetworkFaults faults;

    @Test
    void aNetworkFaultEndingElsewhereLeavesAnExhaustedPoolExhausted() {
        var audit = faults.target(DataSource.class, new DataSourceLiteral("audit"));
        // twice: a request refused for an unrelated reason must not pass for the hang below
        when().get("/orders/audit").then().statusCode(200);
        when().get("/orders/audit").then().statusCode(200);
        try (var exhausted = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders").then().statusCode(503);
            try (var hang = audit.hangs()) {
                when().get("/orders/audit").then().statusCode(503);
            }
            // the flush must not have given the held connections back
            when().get("/orders").then().statusCode(503);
        }
        when().get("/orders").then().statusCode(200);
    }
}
