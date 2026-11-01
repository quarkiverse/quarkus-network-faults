package org.acme.stock;

import static io.quarkiverse.networkfaults.Moment.Http.afterSseEvents;
import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The bug: the stream delivers its first event and goes quiet, and the request waits for the second one. The
 * client has a read timeout of two seconds, and it does not fire: it was cancelled when the response headers
 * arrived, so a stream that has started is bounded by nothing at all.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
class StreamStallsForGoodTest {

    // longer than the read timeout of the client, which is what a reader of the configuration expects to fire here
    private static final Duration PATIENCE = Duration.ofSeconds(3);

    @Inject
    NetworkFaults faults;

    @Test
    void aStalledStreamIsWaitedForAsLongAsItStalls() throws Exception {
        CompletableFuture<Integer> status;
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs(afterSseEvents(1))) {
            status = CompletableFuture.supplyAsync(() -> when()
                    .get("/stock")
                    .then()
                    .extract()
                    .statusCode());

            assertThrows(TimeoutException.class, () -> status.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS),
                    "the request was answered within " + PATIENCE + ": something bounded the stall after all");
        }

        // the stall ends, the rest of the stream arrives and the caller gets the snapshot, as late as the stall was long
        assertEquals(200, status.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS));
    }
}
