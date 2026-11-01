package org.acme.shop;

import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: the status endpoint needs a worker thread, so when the workers are saturated it is unavailable along
 * with the real work, and the monitor pages for an instance that is busy, not broken.
 */
@QuarkusTest
class StatusGoesDownWithTheWorkersTest {

    @Inject
    Faults faults;

    @Test
    void statusIsUnavailableWhenTheWorkersAre() {
        try (var ignored = faults.workerPool().exhausted()) {
            when().get("/catalog").then().statusCode(503);
            when().get("/status").then().statusCode(503);
        }

        when().get("/status").then().statusCode(200);
    }
}
