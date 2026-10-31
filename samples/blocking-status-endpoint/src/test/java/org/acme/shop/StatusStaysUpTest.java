package org.acme.shop;

import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: the status endpoint runs on the event loop, and answers while every worker is busy.
 */
@QuarkusTest
@TestProfile(NonBlockingStatusProfile.class)
class StatusStaysUpTest {

    @Inject
    NetworkFaults faults;

    @Test
    void statusAnswersWhenTheWorkersAreSaturated() {
        try (var ignored = faults.workerPool().exhausted()) {
            when().get("/catalog").then().statusCode(503);
            when().get("/status").then().statusCode(200);
        }

        when().get("/catalog").then().statusCode(200);
    }
}
