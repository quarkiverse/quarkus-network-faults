package io.quarkiverse.resiliencytesting.it;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JUnitIntegrationTest {

    @Inject
    Faults faults;

    @Test
    @Order(1)
    public void leavesAFaultOpenOnPurpose() {
        faults.target(InventoryClient.class).hangs(); // never closed

        when()
                .post("/orders?item=open")
                .then()
                .statusCode(503); // traffic flows into the hang

        assertEquals("hangs", ((FaultsImpl) faults).target(InventoryClient.class).currentFault());
    }

    @Test
    @Order(2)
    public void previousFaultWasResetAfterTheTest() {
        assertEquals("none", ((FaultsImpl) faults).target(InventoryClient.class).currentFault());

        when()
                .post("/orders?item=reset")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    public void guardReportsAFaultThatSawNoTraffic() {
        faults.target(InventoryClient.class).dropConnections();
        faults.target(InventoryClient.class).slow(Duration.ofMillis(10)).end();

        List<String> violations = ((FaultsImpl) faults).takeGuardViolations();

        assertEquals(1, violations.size(), violations.toString());
        String firstViolation = violations.getFirst();
        assertTrue(firstViolation.contains("rest-client:inventory"), firstViolation);
        assertTrue(firstViolation.contains("quarkus.rest-client.inventory.url"), firstViolation);
    }

    @Test
    @Order(4)
    public void guardStaysQuietWhenTrafficFlowed() {
        try (var f = faults.target(InventoryClient.class).slow(Duration.ofMillis(10))) {
            when()
                    .post("/orders?item=traffic")
                    .then()
                    .statusCode(201);
        }
        assertEquals(List.of(), ((FaultsImpl) faults).takeGuardViolations());
    }
}
