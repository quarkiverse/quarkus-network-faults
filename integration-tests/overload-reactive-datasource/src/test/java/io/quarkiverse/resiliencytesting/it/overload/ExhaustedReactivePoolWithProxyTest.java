package io.quarkiverse.resiliencytesting.it.overload;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;

/**
 * Every exhaustion test again with the pools behind their proxies, where a reset connection frees a slot of the pool.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class ExhaustedReactivePoolWithProxyTest extends ExhaustedReactivePoolTest {

    @Test
    void theTargetHasAProxy() {
        assertTrue(faults.targetNames().contains("datasource:<default>:reactive"), faults.targetNames()::toString);
    }

    @Test
    void anExhaustedPoolTakesNoNetworkFault() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            assertThrows(IllegalStateException.class, faults.target(Pool.class)::hangs);
            when().get("/items").then().statusCode(503);
        }

        when().get("/items").then().statusCode(200);
    }

    @Test
    void aPoolWithANetworkFaultIsNotExhausted() {
        try (var ignored = faults.target(Pool.class).unavailable()) {
            assertThrows(IllegalStateException.class, faults.pool(Pool.class)::exhausted);
        }

        when().get("/items").then().statusCode(200);
    }

    @Test
    void aNetworkFaultEndingElsewhereLeavesAnExhaustedPoolExhausted() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            faults.target(Pool.class, AUDIT).unavailable().end();
            when().get("/items").then().statusCode(503);
        }

        when().get("/items").then().statusCode(200);
    }

    @Test
    void droppingTheConnectionsOfAnExhaustedPoolIsRefused() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            when().get("/items").then().statusCode(503);
            // a reset would free the connections the fault holds, and so end it unnoticed
            assertThrows(IllegalStateException.class, faults.target(Pool.class)::dropConnections);
            assertThrows(IllegalStateException.class, faults.all()::dropConnections);
            when().get("/items").then().statusCode(503);
        }

        when().get("/items").then().statusCode(200);
    }
}
