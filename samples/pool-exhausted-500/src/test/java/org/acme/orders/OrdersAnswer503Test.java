package org.acme.orders;

import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: the same full pool, and the client is told to come back later. The connections come back when the
 * window closes, and so does the endpoint.
 */
@QuarkusTest
@TestProfile(PoolTimeoutMapperProfile.class)
class OrdersAnswer503Test {

    @Inject
    Faults faults;

    @Test
    void aFullPoolIsServiceUnavailableWithRetryAfter() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders").then().statusCode(503).header("Retry-After", "1");
        }

        when().get("/orders").then().statusCode(200);
    }
}
