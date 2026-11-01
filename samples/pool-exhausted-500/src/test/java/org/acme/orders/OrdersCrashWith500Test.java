package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.nullValue;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: with every connection in use, {@code getConnection()} waits the acquisition timeout and throws, and
 * nothing maps that exception. The client gets a 500, which says the request is broken rather than the pool busy,
 * and retries at once.
 */
@QuarkusTest
class OrdersCrashWith500Test {

    @Inject
    Faults faults;

    @Test
    void aFullPoolIsAnInternalServerError() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders").then().statusCode(500).header("Retry-After", nullValue());
        }

        when().get("/orders").then().statusCode(200);
    }
}
