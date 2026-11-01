package org.acme.inventory;

import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The bug: with the pool full, the liveness probe fails along with the readiness probe. Kubernetes stops routing
 * to the pod, which is right, and kills it, which is not: the pod was busy, not broken.
 */
@QuarkusTest
class BusyPodIsRestartedTest {

    @Inject
    Faults faults;

    @Test
    void livenessFailsWhenThePoolIsFull() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/q/health/ready").then().statusCode(503);
            // Kubernetes restarts this pod
            when().get("/q/health/live").then().statusCode(503);
        }

        when().get("/q/health/ready").then().statusCode(200);
        when().get("/q/health/live").then().statusCode(200);
    }
}
