package io.quarkiverse.resiliencytesting.it;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class OrderScenariosTest {

    @Inject
    Faults faults;

    @Inject
    @RestClient
    Instance<InventoryClient> inventoryClient;

    @Test
    public void inventoryUnavailable() {
        when()
                .post("/orders/last-failure/clear")
                .then()
                .statusCode(204);

        try (ActiveFault f = faults.target(InventoryClient.class).unavailable()) {
            when()
                    .post("/orders?item=a")
                    .then()
                    .statusCode(503)
                    .body(is("inventory unavailable"));
            assertTrue(lastFailure().toLowerCase().contains("connect"), lastFailure());
        }

        when()
                .post("/orders?item=a")
                .then()
                .statusCode(201);
    }

    @Test
    public void inventoryConnectTimeout() {
        when()
                .post("/orders/last-failure/clear")
                .then()
                .statusCode(204);

        // warm connections would be reused by the client pool; make sure a new connection is needed
        faults.target(InventoryClient.class).dropConnections();

        try (ActiveFault f = faults.target(InventoryClient.class).connectTimeout()) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/orders?item=b")
                    .then()
                    .statusCode(503));
            // @Timeout(1000) and connect-timeout=1000 both bound it; must not wait for the 2s read timeout
            assertTrue(ms < 1_900, "took " + ms + "ms");
            String failure = lastFailure();
            assertTrue(failure.toLowerCase().contains("timeout") || failure.toLowerCase().contains("timed out"), failure);
        }

        when()
                .post("/orders?item=b")
                .then()
                .statusCode(201);
    }

    @Test
    public void inventoryHangs() {
        try (ActiveFault f = faults.target(InventoryClient.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/orders?item=c")
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 900 && ms < 1_900, "expected the 1s @Timeout to fire, took " + ms + "ms");
        }

        when()
                .post("/orders?item=c")
                .then()
                .statusCode(201);
    }

    @Test
    public void inventorySlow() {
        try (ActiveFault f = faults.target(InventoryClient.class).slow(Duration.ofSeconds(10))) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/orders?item=d")
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 900 && ms < 1_900, "expected the 1s @Timeout to fire, took " + ms + "ms");
        }

        when()
                .post("/orders?item=d")
                .then()
                .statusCode(201);
    }

    @Test
    public void circuitBreakerIsClosedAgainAfterTheFault() {
        try (ActiveFault f = faults.target(InventoryClient.class).unavailable()) {
            // requestVolumeThreshold=2, failureRatio=1.0: two failures open the breaker for 60s
            when()
                    .post("/orders?item=e")
                    .then()
                    .statusCode(503);

            when()
                    .post("/orders?item=e")
                    .then()
                    .statusCode(503);

            long requestsBefore = faults.target(InventoryClient.class).traffic().httpRequestsSeen();

            when()
                    .post("/orders?item=e")
                    .then()
                    .statusCode(503);

            assertTrue(lastFailure().contains("CircuitBreakerOpen"), lastFailure());
            // an open breaker short-circuits: nothing reached the proxy (nothing could, it is unbound, but
            // the counter also covers faults where connections are accepted)
            assertEquals(requestsBefore, faults.target(InventoryClient.class).traffic().httpRequestsSeen());
        }

        // ending the fault resets the breaker: no 60s wait
        when()
                .post("/orders?item=e")
                .then()
                .statusCode(201);
    }

    @Test
    public void databaseDiesMidRequestRollsBack() {
        when()
                .post("/orders?item=warm")
                .then()
                .statusCode(201);

        long before = count();

        try (ActiveFault f = faults.target(DataSource.class).diesMidRequest()) {
            when()
                    .post("/orders?item=f")
                    .then()
                    .statusCode(500);
        }

        assertEquals(before, count(), "the insert must not have been committed");

        when()
                .post("/orders?item=f")
                .then()
                .statusCode(201);

        assertEquals(before + 1, count());
    }

    @Test
    public void databaseConnectTimeoutSparesWarmPool() {
        when()
                .post("/orders?item=warm")
                .then()
                .statusCode(201);

        try (ActiveFault f = faults.target(DataSource.class).connectTimeout()) {
            when()
                    .post("/orders?item=g")
                    .then()
                    .statusCode(201); // pooled connection still works

            faults.target(DataSource.class).dropConnections();

            long ms = Stopwatch.millis(() -> when()
                    .post("/orders?item=g")
                    .then()
                    .statusCode(500));

            // driver connectTimeout=2 and acquisition-timeout=2s bound the failure
            assertTrue(ms >= 1_500 && ms < 6_000, "took " + ms + "ms");
        }

        when()
                .post("/orders?item=g")
                .then()
                .statusCode(201);
    }

    @Test
    public void databaseHangsIsBoundedBySocketTimeout() {
        when()
                .get("/orders/count")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(DataSource.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/orders/count")
                    .then()
                    .statusCode(500));

            // validating the pooled connection hits socketTimeout=2, then the replacement connection's
            // handshake hits it again: two bounded waits, never an unbounded hang
            assertTrue(ms >= 1_500 && ms < 6_500, "socketTimeout=2 should bound the failure, took " + ms + "ms");
        }

        when()
                .get("/orders/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void droppedConnectionsAreReplacedWithoutLeaking() {
        when()
                .post("/orders?item=warm")
                .then()
                .statusCode(201);

        faults.target(DataSource.class).dropConnections();

        // validated on borrow, so the pool notices and replaces the dead connection
        when()
                .get("/orders/count")
                .then()
                .statusCode(200);

        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> faults.target(DataSource.class).traffic().connections() == 1);
    }

    @Test
    public void reactivePoolEvictsDroppedConnectionsImmediately() {
        when()
                .get("/reactive/ping")
                .then()
                .statusCode(200);

        assertTrue(faults.target(Pool.class).traffic().connections() >= 1);

        faults.target(Pool.class).dropConnections();

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> faults.target(Pool.class).traffic().connections() == 0);

        when()
                .get("/reactive/ping")
                .then()
                .statusCode(200);
    }

    @Test
    public void reactiveHangIsBoundedByTheMutinyTimeout() {
        when()
                .get("/reactive/ping")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(Pool.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/reactive/ping")
                    .then()
                    .statusCode(504));
            assertTrue(ms >= 1_500 && ms < 4_000, "ifNoItem().after(2s) should fire, took " + ms + "ms");
        }

        when()
                .get("/reactive/ping")
                .then()
                .statusCode(200);
    }

    @Test
    public void everythingDown() {
        try (ActiveFault f = faults.all().unavailable()) {
            when()
                    .post("/orders?item=h")
                    .then()
                    .statusCode(503);

            when()
                    .get("/orders/count")
                    .then()
                    .statusCode(500);

            when()
                    .get("/reactive/ping")
                    .then()
                    .statusCode(504);
        }

        when()
                .post("/orders?item=h")
                .then()
                .statusCode(201);
    }

    @Test
    public void targetsCanBeAddressedByAnInjectedInstance() {
        try (ActiveFault f = faults.target(inventoryClient).unavailable()) {
            when()
                    .post("/orders?item=inst")
                    .then()
                    .statusCode(503)
                    .body(is("inventory unavailable"));
        }

        when()
                .post("/orders?item=inst")
                .then()
                .statusCode(201);
    }

    @Test
    public void aBeanNoExtensionRecognisesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> faults.target(String.class));
    }

    private long count() {
        return Long.parseLong(when()
                .get("/orders/count")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    private String lastFailure() {
        return when()
                .get("/orders/last-failure")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

}
