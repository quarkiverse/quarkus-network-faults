package org.acme.inventory;

import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: the database is a readiness concern only. The pod leaves the rotation while its pool is full and
 * rejoins when connections are back, and it is never restarted for being busy.
 */
@QuarkusTest
@TestProfile(ReadinessOnlyProfile.class)
class BusyPodStaysUpTest {

    @Inject
    Faults faults;

    @Test
    void onlyReadinessFailsWhenThePoolIsFull() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/q/health/ready").then().statusCode(503);
            when().get("/q/health/live").then().statusCode(200);
        }

        when().get("/q/health/ready").then().statusCode(200);
    }
}
