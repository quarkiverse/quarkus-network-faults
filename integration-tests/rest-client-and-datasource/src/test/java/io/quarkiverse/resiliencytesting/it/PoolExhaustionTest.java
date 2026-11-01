package io.quarkiverse.resiliencytesting.it;

import static io.quarkiverse.resiliencytesting.Moment.onNextWrite;
import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.agroal.DataSource.DataSourceLiteral;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * A connection pool running out, the way most outages start: the database accepts no new connections and the pool
 * cannot grow, or the database hangs and every pooled connection is busy waiting for it. Either way the next request
 * fails on the pool's acquisition timeout, not on the database's, and work that needs no connection keeps flowing.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class PoolExhaustionTest {

    @Inject
    Faults faults;

    @Test
    @EnabledOnOs(OS.LINUX)
    public void anEmptyPoolThatCannotGrowFailsOnItsAcquisitionTimeout() {
        Target database = faults.target(DataSource.class);
        when()
                .get("/orders/count")
                .then()
                .statusCode(200);

        // nothing idle in the pool, and no new connection gets through
        database.dropConnections();
        try (ActiveFault f = database.connectTimeout()) {
            long ms = Stopwatch.millis(() -> when()
                    .get("/orders/count")
                    .then()
                    .statusCode(500));
            // quarkus.datasource.jdbc.acquisition-timeout=PT2S bounds the wait; nothing reached the proxy
            assertTrue(ms >= 1_500 && ms < 4_000, "expected the acquisition timeout to end the wait, took " + ms + "ms");
            assertEquals(0, database.traffic().connections());
        }

        when()
                .get("/orders/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void aPoolFilledByAHungDatabaseFailsTheNextRequestFastAndLeavesOtherWorkAlone() throws Exception {
        // the audit pool holds two connections, gives up acquiring after one second, and its database timeouts are
        // ten seconds: a request that fails after about a second can only have failed on the pool. The pool has to be
        // full of established connections first: the acquisition timeout bounds the wait for a connection that
        // exists, and in our runs a request for which the pool was creating one against a hung handshake did not
        // fail at that timeout
        Target audit = faults.target(DataSource.class, new DataSourceLiteral("audit"));
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            // two overlapping requests make the pool open its second connection, with no fault involved; the fault
            // is applied only once both are done, so that both connections are established and idle, not half-way
            // through a handshake the fault would hold
            List<Future<Integer>> warmUp = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                warmUp.add(callers.submit(() -> when().get("/audit/slow-count").andReturn().statusCode()));
            }
            audit.traffic().awaitConnections(2, Duration.ofSeconds(5));
            for (Future<Integer> status : warmUp) {
                assertEquals(200, status.get(5, TimeUnit.SECONDS));
            }

            // held at the first write, so a held connection is one a request is using
            try (ActiveFault f = audit.hangs(onNextWrite())) {
                List<Future<Integer>> held = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    held.add(callers.submit(() -> when().get("/audit/count").andReturn().statusCode()));
                }
                // both pooled connections are busy before the request that should not get one is sent
                audit.traffic().awaitHeldConnections(2, Duration.ofSeconds(5));

                long ms = Stopwatch.millis(() -> when()
                        .get("/audit/count")
                        .then()
                        .statusCode(500));
                assertTrue(ms >= 800 && ms < 3_000, "expected the 1s acquisition timeout to end the wait, took " + ms + "ms");

                // a request that needs no audit connection is not held up: the bulkhead holds
                when()
                        .get("/orders/price")
                        .then()
                        .statusCode(200);

                f.end();
                // the fault gone, the two stuck requests complete at once: ending a fault flushes the pool, so they end
                // with an error on a closed connection rather than waiting out the database's ten seconds
                for (Future<Integer> status : held) {
                    assertTrue(status.get(5, TimeUnit.SECONDS) >= 200);
                }
            }
        }
    }
}
