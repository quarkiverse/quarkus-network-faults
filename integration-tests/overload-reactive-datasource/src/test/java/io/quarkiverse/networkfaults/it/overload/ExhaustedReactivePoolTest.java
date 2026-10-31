package io.quarkiverse.networkfaults.it.overload;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.runtime.internal.NetworkFaultsImpl;
import io.quarkus.reactive.datasource.ReactiveDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;

@QuarkusTest
class ExhaustedReactivePoolTest {

    static final ReactiveDataSource AUDIT = new ReactiveDataSource.ReactiveDataSourceLiteral("audit");
    private static final ReactiveDataSource BURST = new ReactiveDataSource.ReactiveDataSourceLiteral("burst");
    private static final ReactiveDataSource PATIENT = new ReactiveDataSource.ReactiveDataSourceLiteral("patient");
    private static final ReactiveDataSource HASTY = new ReactiveDataSource.ReactiveDataSourceLiteral("hasty");

    @Inject
    NetworkFaults faults;

    @Inject
    @ReactiveDataSource("patient")
    Pool patient;

    @Inject
    @ReactiveDataSource("hasty")
    Pool hasty;

    @Test
    void exhaustedPoolAnswersServiceUnavailableThenRecovers() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            when()
                    .get("/items")
                    .then()
                    .statusCode(503)
                    .header("Retry-After", "1");
            // the bare Vert.x bean is the same pool
            when().get("/items/bare").then().statusCode(503);
        }

        when().get("/items").then().statusCode(200);
        when().get("/items/bare").then().statusCode(200);
    }

    @Test
    void theBareVertxPoolBeanNamesTheSamePool() {
        try (var ignored = faults.pool(io.vertx.sqlclient.Pool.class).exhausted()) {
            when().get("/items").then().statusCode(503);
        }

        when().get("/items").then().statusCode(200);
    }

    @Test
    void namedDatasourceIsExhaustedOnItsOwn() {
        try (var ignored = faults.pool(Pool.class, AUDIT).exhausted()) {
            when().get("/items/audit").then().statusCode(503);
            // the default datasource is untouched
            when().get("/items").then().statusCode(200);
        }

        when().get("/items/audit").then().statusCode(200);
    }

    @Test
    void poolWithoutAWaitQueueIsExhaustedWithoutAGuard() {
        // its callers never wait, so nothing can be watched: the after-each guard must not report this fault
        try (var ignored = faults.pool(Pool.class, BURST).exhausted()) {
            when().get("/items/burst").then().statusCode(503);
        }

        when().get("/items/burst").then().statusCode(200);
    }

    @Test
    void poolWithAWaitTooShortToWatchIsExhaustedWithoutAGuard() throws InterruptedException {
        warmUp(hasty);
        // its callers wait for a millisecond, too short for the sampler to see reliably: the fault must not be judged
        try (var ignored = faults.pool(Pool.class, HASTY).exhausted()) {
            when().get("/items/hasty").then().statusCode(503);
        }

        assertEquals(List.of(), ((NetworkFaultsImpl) faults).takeGuardViolations());
        when().get("/items/hasty").then().statusCode(200);
    }

    @Test
    void aFailedTakeLeavesThePoolWhole() {
        // the application keeps one connection past the take's own wait, which is shorter than the pool's timeout
        var held = patient.getConnection().await().indefinitely();
        try {
            assertThrows(IllegalStateException.class, () -> faults.pool(Pool.class, PATIENT).exhausted());
        } finally {
            held.close().await().indefinitely();
        }

        // every connection is free again, so the whole pool can be taken
        faults.pool(Pool.class, PATIENT).exhausted().end();
        // nothing was meant to wait for that exhaustion
        ((NetworkFaultsImpl) faults).takeGuardViolations();
    }

    /**
     * Opening a connection takes longer than the hasty pool's millisecond, so the first calls fail while the pool opens
     * one in the background; once it is pooled, every call gets it at once.
     */
    private static void warmUp(Pool pool) throws InterruptedException {
        // the second attempt usually succeeds; together these bound the wait at a second
        int attempts = 50;
        long pauseMillis = 20;
        for (int attempt = 1;; attempt++) {
            try {
                pool.getConnection().await().indefinitely().closeAndAwait();
                return;
            } catch (RuntimeException e) {
                if (attempt == attempts) {
                    throw e;
                }
                Thread.sleep(pauseMillis);
            }
        }
    }

    @Test
    void poolIsNamedAfterTheDatasource() {
        assertEquals("datasource:<default>:reactive", faults.pool(Pool.class).name());
        assertEquals("datasource:audit:reactive", faults.pool(Pool.class, AUDIT).name());
    }

    @Test
    void faultNothingWaitedOnIsReportedByTheGuard() {
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            // this endpoint never touches the database, so the fault cannot have been tested
            when().get("/items/static").then().statusCode(200);
        }

        // read the guard directly; the after-each callback would otherwise fail this test
        List<String> violations = ((NetworkFaultsImpl) faults).takeGuardViolations();
        assertEquals(1, violations.size(), violations::toString);
        assertTrue(violations.get(0).contains("datasource:<default>:reactive"), violations.get(0));
    }
}
