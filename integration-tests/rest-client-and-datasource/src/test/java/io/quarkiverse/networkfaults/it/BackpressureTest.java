package io.quarkiverse.networkfaults.it;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.Advanced;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Backpressure under a slow network: the application streams a large export to the inventory service, and has to
 * build it at the pace the network takes it rather than ahead of it.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class BackpressureTest {

    private static final long MB = 1024 * 1024;

    @Inject
    NetworkFaults faults;

    @Test
    public void exportIsPacedByTheNetwork() {
        // ExportResource sends 64 MB: at 32 MB/s the upload takes about two seconds
        try (ActiveFault f = faults.target(ExportClient.class).advanced().bandwidth(32 * MB,
                Advanced.Direction.UPSTREAM)) {
            String[] millis = when()
                    .post("/export")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString()
                    .split(" ");
            long produced = Long.parseLong(millis[0]);
            long uploaded = Long.parseLong(millis[1]);
            assertTrue(uploaded >= 1_800,
                    "64 MB at 32 MB/s should take about 2 s, the upload took " + uploaded + " ms");
            // with backpressure the last chunk is produced shortly before the upload is acknowledged; an application
            // that buffers the export produces it in a few milliseconds and spends the rest of the time sending it
            assertTrue(produced >= uploaded / 2, "the export was produced in " + produced + " ms and uploaded in "
                    + uploaded + " ms: it was built ahead of the network, not paced by it");
        }
    }
}
