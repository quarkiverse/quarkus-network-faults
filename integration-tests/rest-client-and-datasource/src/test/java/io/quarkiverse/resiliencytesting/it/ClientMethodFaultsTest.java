package io.quarkiverse.resiliencytesting.it;

import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Traffic;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Faults on the requests one method of a REST Client sends: the other method of the same client, over the same pool
 * of connections, keeps working, or fails in a way of its own; and the requests each method sends, counted apart.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class ClientMethodFaultsTest {

    @Inject
    Faults faults;

    @Inject
    @RestClient
    Instance<InventoryClient> inventoryClient;

    private static void streamCompletes() {
        when()
                .get("/stock/updates")
                .then()
                .statusCode(200)
                .body(is("1,2,3"));
    }

    @Test
    public void reserveHangsWhileTheStreamKeepsWorking() {
        try (ActiveFault f = faults.target(InventoryClient::reserve).hangs()) {
            when()
                    .post("/orders?item=a")
                    .then()
                    .statusCode(503)
                    .body(is("inventory unavailable"));
            streamCompletes();
        }

        when()
                .post("/orders?item=a")
                .then()
                .statusCode(201);
    }

    @Test
    public void theStreamIsCutWhileReserveKeepsWorking() {
        try (ActiveFault f = faults.target(InventoryClient::stockUpdates).diesMidRequest(afterSseEvents(1))) {
            when()
                    .post("/orders?item=b")
                    .then()
                    .statusCode(201);
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(containsString(" after [1]"));
        }

        streamCompletes();
    }

    @Test
    public void reserveFailsWithTheConfiguredStatusWhileTheStreamKeepsWorking() {
        try (ActiveFault f = faults.target(InventoryClient::reserve).http().respondsWith(503)) {
            WebApplicationException failure = assertThrows(WebApplicationException.class,
                    () -> inventoryClient.get().reserve("widget"));
            assertEquals(503, failure.getResponse().getStatus());
            streamCompletes();
        }

        assertEquals("reserved", inventoryClient.get().reserve("widget"));
    }

    @Test
    public void reserveHangsWhileTheStreamFailsWithAStatus() {
        try (ActiveFault hang = faults.target(InventoryClient::reserve).hangs();
                ActiveFault error = faults.target(InventoryClient::stockUpdates).http().respondsWith(500)) {
            when()
                    .post("/orders?item=c")
                    .then()
                    .statusCode(503)
                    .body(is("inventory unavailable"));
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(containsString("WebApplicationException"));
        }

        streamCompletes();
    }

    @Test
    public void eachMethodCountsItsOwnRequests() {
        Traffic reserve = faults.target(InventoryClient::reserve).traffic();
        Traffic stock = faults.target(InventoryClient::stockUpdates).traffic();
        long reservedBefore = reserve.httpRequestsSeen();
        long streamedBefore = stock.httpRequestsSeen();

        when()
                .post("/orders?item=d")
                .then()
                .statusCode(201);
        streamCompletes();
        streamCompletes();

        assertEquals(1, reserve.httpRequestsSeen() - reservedBefore, reserve.httpRequests().toString());
        assertEquals(2, stock.httpRequestsSeen() - streamedBefore, stock.httpRequests().toString());
    }

    @Test
    public void everyOtherReserveIsSlow() {
        // tail latency: one call in two takes longer than the @Timeout(1000) on the caller
        try (ActiveFault f = faults.target(InventoryClient::reserve).http().every(2).slow(Duration.ofMillis(1500))) {
            when()
                    .post("/orders?item=slow-1")
                    .then()
                    .statusCode(503);
            when()
                    .post("/orders?item=fast-2")
                    .then()
                    .statusCode(201);
            when()
                    .post("/orders?item=slow-3")
                    .then()
                    .statusCode(503);
        }
    }
}
