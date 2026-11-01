package io.quarkiverse.resiliencytesting.kafka;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The sample application as an ordinary test sees it: no profile, so the broker is reached directly.
 */
@QuarkusTest
public class EventsWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void eventsRoundTripThroughTheBroker() {
        when()
                .post("/events?event=plain")
                .then()
                .statusCode(202);
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> when()
                        .get("/events")
                        .then()
                        .statusCode(200)
                        .body("$", hasItem("plain")));
    }
}
