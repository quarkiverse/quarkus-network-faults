package io.quarkiverse.resiliencytesting.hr;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;

/**
 * A Mutiny session factory is a way to reach the reactive connection of its datasource.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class HibernateReactiveFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void theSessionFactoryResolvesToTheReactiveConnection() {
        assertEquals(faults.target(Pool.class).name(), faults.target(Mutiny.SessionFactory.class).name());
    }

    @Test
    public void databaseUnavailable() {
        when()
                .post("/fruits?name=a")
                .then()
                .statusCode(200)
                .body(is("created"));

        try (ActiveFault f = faults.target(Mutiny.SessionFactory.class).unavailable()) {
            when()
                    .get("/fruits/count")
                    .then()
                    .statusCode(503);
        }

        when()
                .get("/fruits/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void databaseHangsIsBoundedByTheCallersTimeout() {
        when()
                .get("/fruits/count")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(Mutiny.SessionFactory.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/fruits/count")
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 1_500 && ms < 4_000, "expected the caller's 2s bound, took " + ms + "ms");
        }

        when()
                .get("/fruits/count")
                .then()
                .statusCode(200);
    }
}
