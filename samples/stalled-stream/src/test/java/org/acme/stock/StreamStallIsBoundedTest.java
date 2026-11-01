package org.acme.stock;

import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The fix: an idle timeout on the stream. The same stall after the first event, and one second later the stream
 * fails, the application answers with the event it has, and the next request reads the whole stream again.
 */
@QuarkusTest
@TestProfile(IdleTimeoutProfile.class)
@QuarkusTestResource(InventoryServer.class)
class StreamStallIsBoundedTest {

    // generous on purpose: a slow CI runner must not fail this, and the bug test shows the alternative is no answer
    // at all
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Inject
    Faults faults;

    @Test
    void aStalledStreamFailsAfterOneQuietSecond() {
        try (ActiveFault fault = faults.target(InventoryClient.class).hangs(afterSseEvents(1))) {
            long ms = Stopwatch.millis(() -> assertTimeoutPreemptively(PATIENCE, () -> when()
                    .get("/stock")
                    .then()
                    .statusCode(502)
                    // the idle timeout is what failed the stream, with the one event that had arrived
                    .body(is("TimeoutException after [1]"))));
            // the stream waited its quiet second before failing, with slack for scheduling
            assertTrue(ms >= 900, "expected the 1 s idle timeout to wait before failing the stream, took " + ms + " ms");
        }

        when()
                .get("/stock")
                .then()
                .statusCode(200)
                .body(is("1,2,3"));
    }
}
