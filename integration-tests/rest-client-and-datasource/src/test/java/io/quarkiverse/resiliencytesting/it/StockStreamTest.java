package io.quarkiverse.resiliencytesting.it;

import static io.quarkiverse.resiliencytesting.Moment.Http.afterHeaders;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Faults in the middle of a streamed response: the REST Client consumes a Server-Sent Events stream that is cut or
 * stalls after some of its events.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class StockStreamTest {

    @Inject
    Faults faults;

    private static void streamCompletes() {
        when()
                .get("/stock/updates")
                .then()
                .statusCode(200)
                .body(is("1,2,3"));
    }

    @Test
    public void streamWithoutFaults() {
        streamCompletes();
    }

    @Test
    public void streamCutAfterTheFirstEvent() {
        try (ActiveFault f = faults.target(InventoryClient.class).diesMidRequest(afterSseEvents(1))) {
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(containsString(" after [1]"));
        }
        streamCompletes();
    }

    @Test
    public void streamCutRightAfterTheHeaders() {
        try (ActiveFault f = faults.target(InventoryClient.class).diesMidRequest(afterHeaders())) {
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(containsString(" after []"));
        }
        streamCompletes();
    }

    @Test
    public void streamStallsAfterTheFirstEvent() {
        try (ActiveFault f = faults.target(InventoryClient.class).hangs(afterSseEvents(1))) {
            long start = System.nanoTime();
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(containsString(" after [1]"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            // Finding: the client's read-timeout (2s) does not fire. Vert.x cancels it when the response headers
            // arrive, so once a stream has started only the application's own idle timeout (3s here) bounds a stall.
            assertTrue(ms >= 2_900 && ms < 6_000, "expected the resource's 3s idle timeout to bound the stall, took "
                    + ms + "ms");
        }
        streamCompletes();
    }

    @Test
    public void streamEndsCleanlyAfterTheFirstEvent() {
        // the stream is chunked, so the client knows the last chunk never came and fails the Multi, with a closed
        // connection rather than the reset of diesMidRequest
        try (ActiveFault f = faults.target(InventoryClient.class).closes(afterSseEvents(1))) {
            when()
                    .get("/stock/updates")
                    .then()
                    .statusCode(502)
                    .body(is("HttpClosedException after [1]"));
        }
        streamCompletes();
    }
}
