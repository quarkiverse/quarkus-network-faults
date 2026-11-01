package org.acme.reports;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.startsWith;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: a bounded queue. A request that finds no free worker and no room in the queue is answered 503 at once,
 * so the client can try another instance or come back later, and nothing is served to a client that left.
 */
@QuarkusTest
@TestProfile(BoundedQueueProfile.class)
class ExcessIsRejectedTest {

    @Inject
    Faults faults;

    @Test
    void aRequestThatFindsNoWorkerIsRejectedAtOnce() {
        try (var ignored = faults.workerPool().exhausted()) {
            when().get("/reports").then().statusCode(503);
            when().get("/q/health/live").then().statusCode(200);
        }

        when().get("/reports").then().statusCode(200).body(startsWith("report rendered on executor-thread-"));
    }
}
