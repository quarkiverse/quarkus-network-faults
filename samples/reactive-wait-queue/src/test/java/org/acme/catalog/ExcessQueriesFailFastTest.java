package org.acme.catalog;

import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;

/**
 * The fix: a bounded wait queue. A query that finds no free connection and no room in the queue fails at once,
 * and the endpoint answers 503 with Retry-After while the client is still there to read it.
 */
@QuarkusTest
@TestProfile(BoundedWaitQueueProfile.class)
class ExcessQueriesFailFastTest {

    @Inject
    Faults faults;

    @Test
    void aQueryThatFindsNoConnectionFailsAtOnce() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            when().get("/catalog").then().statusCode(503).header("Retry-After", "1");
        }

        when().get("/catalog").then().statusCode(200);
    }
}
