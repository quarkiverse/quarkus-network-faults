package io.quarkiverse.networkfaults.it.overload;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The combination the docs recommend: a network fault on a dependency, watched for the event loop that ends up
 * waiting on it.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class WatchedEventLoopsWithNetworkHangTest {

    @Inject
    NetworkFaults faults;

    @Test
    void aHandlerThatWaitsOnAHungDatasourceOnTheEventLoopFailsTheWatch() {
        // a pooled connection, so the query below goes through the proxy rather than opening one
        when().get("/loop/db").then().statusCode(200);

        var watch = faults.eventLoops().watched();
        try (var ignored = faults.target(DataSource.class).hangs()) {
            // the query waits for socketTimeout, then fails
            when().get("/loop/db").then().statusCode(500);
        }

        AssertionError failure = assertThrows(AssertionError.class, watch::end);
        assertTrue(failure.getMessage().contains("vert.x-eventloop-thread"), failure.getMessage());
    }
}
