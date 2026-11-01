package io.quarkiverse.resiliencytesting.it.overload;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ExhaustedWorkerPoolTest {

    @Inject
    Faults faults;

    @Test
    void blockingEndpointIsRejectedWhileNonBlockingOneStillAnswers() {
        try (var ignored = faults.workerPool().exhausted()) {
            // no worker thread and no queue: Quarkus turns the rejected dispatch into a 503
            when().get("/work").then().statusCode(503);
            // the event loop is not involved in the exhaustion, so non-blocking endpoints and liveness probes still answer
            when().get("/work/status").then().statusCode(200);
            when().get("/q/health/live").then().statusCode(200);
        }

        when().get("/work").then().statusCode(200).body(startsWith("done on executor-thread-"));
    }

    @Test
    void targetIsTheWorkerPool() {
        assertEquals("worker-pool", faults.workerPool().name());
    }
}
