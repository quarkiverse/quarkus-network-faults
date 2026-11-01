package io.quarkiverse.resiliencytesting.redis;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Redis from Dev Services behind the proxy; the client is addressed by the data source it injects.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class RedisFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void redisUnavailable() {
        when()
                .post("/counters/a")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(RedisDataSource.class).unavailable()) {
            when()
                    .post("/counters/a")
                    .then()
                    .statusCode(503);
        }

        when()
                .post("/counters/a")
                .then()
                .statusCode(200);
    }

    @Test
    public void redisHangsIsBoundedByTheTimeout() {
        when()
                .post("/counters/b")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(RedisDataSource.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/counters/b")
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 1_500 && ms < 4_000, "expected quarkus.redis.timeout=2s to bound the wait, took " + ms + "ms");
        }

        when()
                .get("/counters/b")
                .then()
                .statusCode(200);
    }

    @Test
    public void redisDiesMidRequest() {
        when()
                .post("/counters/c")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(RedisDataSource.class).diesMidRequest()) {
            when()
                    .post("/counters/c")
                    .then()
                    .statusCode(503);
        }

        when()
                .get("/counters/c")
                .then()
                .statusCode(200);
    }

    @Test
    public void thePoolReplacesDroppedConnections() {
        when()
                .post("/counters/d")
                .then()
                .statusCode(200)
                .body(is("1"));

        faults.target(RedisDataSource.class).dropConnections();

        when()
                .post("/counters/d")
                .then()
                .statusCode(200)
                .body(is("2"));
    }
}
