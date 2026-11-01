package org.acme.stock;

import static io.restassured.RestAssured.when;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: the JDBC call runs on a worker thread. It still waits the acquisition timeout when the pool is full,
 * and the endpoint still answers 503, but no event loop waits with it.
 */
@QuarkusTest
@TestProfile(BlockingProfile.class)
class JdbcRunsOnAWorkerTest {

    @Inject
    Faults faults;

    @Test
    void aFullPoolBlocksNoEventLoop() {
        try (var watch = faults.eventLoops().watched()) {
            try (var ignored = faults.pool(DataSource.class).exhausted()) {
                when().get("/stock").then().statusCode(503);
            }
        }

        when().get("/stock").then().statusCode(200);
    }
}
