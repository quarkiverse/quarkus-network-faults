package io.quarkiverse.resiliencytesting.mongodb;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * MongoDB from Dev Services behind the proxy; the client is addressed by the MongoClient it injects.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class MongoFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void mongoUnavailable() {
        when()
                .post("/books?title=a")
                .then()
                .statusCode(200)
                .body(is("added"));

        try (ActiveFault f = faults.target(MongoClient.class).unavailable()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/books/count")
                    .then()
                    .statusCode(503));
            assertTrue(ms < 4_000, "expected server-selection-timeout=2s to bound the failure, took " + ms + "ms");
        }

        when()
                .get("/books/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void mongoHangsIsBoundedByTheReadTimeout() {
        when()
                .get("/books/count")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(MongoClient.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/books/count")
                    .then()
                    .statusCode(503));
            assertTrue(ms < 6_000, "expected read-timeout=2s to bound the wait, took " + ms + "ms");
        }

        when()
                .get("/books/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void mongoDiesMidRequest() {
        try (ActiveFault f = faults.target(MongoClient.class).diesMidRequest()) {
            when()
                    .get("/books/count")
                    .then()
                    .statusCode(503);
        }

        when()
                .get("/books/count")
                .then()
                .statusCode(200);
    }
}
