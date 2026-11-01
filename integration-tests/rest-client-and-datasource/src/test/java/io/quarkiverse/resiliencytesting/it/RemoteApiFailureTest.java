package io.quarkiverse.resiliencytesting.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class RemoteApiFailureTest {

    @Inject
    Faults faults;

    @Inject
    @RestClient
    Instance<InventoryClient> inventoryClient;

    @Test
    public void restClientSurfacesTheConfiguredStatus() {
        try (ActiveFault f = faults.target(InventoryClient.class).http().respondsWith(503)) {
            WebApplicationException failure = assertThrows(WebApplicationException.class,
                    () -> inventoryClient.get().reserve("widget"));
            assertEquals(503, failure.getResponse().getStatus());
        }

        // ending the fault restores the real upstream
        assertEquals("reserved", inventoryClient.get().reserve("widget"));
    }

    @Test
    public void restClientRecoversAfterTheConfiguredNumberOfFailures() {
        try (ActiveFault f = faults.target(InventoryClient.class).http().forFirst(1).http().respondsWith(503)) {
            WebApplicationException failure = assertThrows(WebApplicationException.class,
                    () -> inventoryClient.get().reserve("widget"));
            assertEquals(503, failure.getResponse().getStatus());

            // the next call reaches the real upstream again
            assertEquals("reserved", inventoryClient.get().reserve("widget"));
        }
    }

    @Test
    public void restClientSeesTheResponseBodyAndHeaders() {
        try (ActiveFault f = faults.target(InventoryClient.class)
                .http().respondsWith(429)
                .contentType("application/json")
                .body("{\"error\":\"rate limited\"}")
                .header("Retry-After", "30")) {
            WebApplicationException failure = assertThrows(WebApplicationException.class,
                    () -> inventoryClient.get().reserve("widget"));
            Response response = failure.getResponse();
            assertEquals(429, response.getStatus());
            assertEquals("30", response.getHeaderString("Retry-After"));
            assertTrue(response.readEntity(String.class).contains("rate limited"));
        }
    }

    @Test
    public void everyOtherCallFails() {
        try (ActiveFault f = faults.target(InventoryClient.class).http().every(2).http().respondsWith(503)) {
            assertThrows(WebApplicationException.class, () -> inventoryClient.get().reserve("widget"));
            assertEquals("reserved", inventoryClient.get().reserve("widget"));
            assertThrows(WebApplicationException.class, () -> inventoryClient.get().reserve("widget"));
            assertEquals("reserved", inventoryClient.get().reserve("widget"));
        }
    }

    @Test
    public void callsFailAfterTheFirstOnes() {
        try (ActiveFault f = faults.target(InventoryClient.class).http().after(1).http().respondsWith(503)) {
            assertEquals("reserved", inventoryClient.get().reserve("widget"));
            assertThrows(WebApplicationException.class, () -> inventoryClient.get().reserve("widget"));
            assertThrows(WebApplicationException.class, () -> inventoryClient.get().reserve("widget"));
        }
    }

    @Test
    public void restClientReceivesAnOversizedBody() {
        try (ActiveFault f = faults.target(InventoryClient.class).http().respondsWith(200)
                .contentType("text/plain").bodyOfLength(20_000_000)) {
            assertEquals(20_000_000, inventoryClient.get().reserve("widget").length());
        }
    }
}
