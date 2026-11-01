package io.quarkiverse.resiliencytesting.it;

import static io.quarkiverse.resiliencytesting.Moment.onNextWrite;
import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.HttpRequestRecord;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.Attempts;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class FaultToleranceTest {

    @Inject
    Faults faults;

    @Test
    public void retryAbsorbsAFaultThatClearsInTime() throws Exception {
        Target api = faults.target(InventoryClient.class);
        long before = api.traffic().httpRequestsSeen();
        ActiveFault fault = api.diesMidRequest(onNextWrite());
        CompletableFuture<Integer> status = CompletableFuture.supplyAsync(
                () -> when()
                        .post("/orders/retrying?item=retry")
                        .then()
                        .extract()
                        .statusCode());
        // the proxy sees every attempt before it resets it: let a few fail, then clear the fault
        await().atMost(5, TimeUnit.SECONDS).until(() -> api.traffic().httpRequestsSeen() >= before + 3);
        fault.end();
        assertEquals(201, (int) status.get(10, TimeUnit.SECONDS),
                "retries should have succeeded once the fault cleared");
    }

    @Test
    public void retryGivesUpWhenTheFaultOutlastsIt() {
        try (ActiveFault fault = faults.target(InventoryClient.class).unavailable()) {
            long start = System.nanoTime();
            when()
                    .post("/orders/retrying?item=retry")
                    .then()
                    .statusCode(503);
            long ms = (System.nanoTime() - start) / 1_000_000;
            // 8 retries x 250ms delay, each attempt refused instantly
            assertTrue(ms >= 1_800 && ms < 4_000, "expected the retry window to be exhausted, took " + ms + "ms");
            String failure = when()
                    .get("/orders/last-failure")
                    .then()
                    .extract()
                    .asString();
            assertTrue(failure.toLowerCase().contains("connect"), failure);
        }
        when()
                .post("/orders/retrying?item=retry")
                .then()
                .statusCode(201);
    }

    @Test
    public void everyRetryAttemptIsCounted() {
        Target api = faults.target(InventoryClient.class);
        long before = api.traffic().httpRequestsSeen();
        try (ActiveFault fault = api.diesMidRequest(onNextWrite())) {
            when()
                    .post("/orders/retrying?item=count")
                    .then()
                    .statusCode(503);
        }
        // @Retry(maxRetries = 8): the first attempt plus eight retries all reached the proxy
        List<HttpRequestRecord> requests = api.traffic().httpRequests();
        assertEquals(9, api.traffic().httpRequestsSeen() - before, requests.toString());
        // the other tests of the application have sent requests of their own before these
        Attempts attempts = Attempts.last(requests, 9);
        for (HttpRequestRecord r : attempts.records()) {
            assertEquals("POST /inventory/reserve", r.method() + " " + r.target(), requests.toString());
        }
        // every retry sent the same body, and @Retry(delay = 250) kept them apart
        attempts.assertSameBody().assertGapsAtLeast(Duration.ofMillis(200));
    }

    @Test
    public void retryDoesNotHelpAgainstAHangUnlessThereIsATimeout() {
        // reserveWithRetry has no @Timeout: with a hung upstream the *client* read timeout (2s) is the only bound,
        // and it is paid on every attempt. This test documents that cost.
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs()) {
            long start = System.nanoTime();
            when()
                    .post("/orders/retrying?item=hang")
                    .then()
                    .statusCode(503);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 2_000, "at least one full read timeout was paid, took " + ms + "ms");
        }
    }
}
