package org.acme.orders;

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
 * The bug: the inventory accepts the connection and never answers, and the order waits with it. Nothing in the
 * application bounds that wait, so the REST Client's default does: thirty seconds, per request, while the caller,
 * the load balancer in front and the worker thread serving the request all hold on.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
class OrdersHangWithTheInventoryTest {

    // long enough that any timeout a caller would accept has fired, short enough to keep the test short: the
    // default read timeout of the REST Client is thirty seconds
    private static final Duration PATIENCE = Duration.ofSeconds(3);

    @Inject
    NetworkFaults faults;

    @Test
    void anOrderWaitsAsLongAsTheInventoryHangs() throws Exception {
        CompletableFuture<Integer> status;
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs()) {
            status = CompletableFuture.supplyAsync(() -> when()
                    .post("/orders?item=apple")
                    .then()
                    .extract()
                    .statusCode());

            assertThrows(TimeoutException.class, () -> status.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS),
                    "the order was answered within " + PATIENCE + ": something bounded the wait after all");
        }

        // the inventory answers again and the order goes through: the caller got a 201, as late as the hang was long
        assertEquals(201, status.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS));
    }
}
