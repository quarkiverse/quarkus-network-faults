package io.quarkiverse.networkfaults.it.overload;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class WatchedEventLoopsTest {

    @Inject
    NetworkFaults faults;

    @Test
    void aHandlerThatBlocksTheEventLoopFailsTheWatch() {
        var watch = faults.eventLoops().watched();
        when().get("/loop/blocking").then().statusCode(200);

        AssertionError failure = assertThrows(AssertionError.class, watch::end);
        assertTrue(failure.getMessage().contains("vert.x-eventloop-thread"), failure.getMessage());
    }

    @Test
    void aHandlerThatDoesNotBlockPassesTheWatch() {
        try (var ignored = faults.eventLoops().watched()) {
            when().get("/loop/fine").then().statusCode(200);
        }
    }

    @Test
    void aBlockBeforeTheWatchIsNotHeldAgainstIt() {
        when().get("/loop/blocking").then().statusCode(200);

        try (var ignored = faults.eventLoops().watched()) {
            when().get("/loop/fine").then().statusCode(200);
        }
    }
}
