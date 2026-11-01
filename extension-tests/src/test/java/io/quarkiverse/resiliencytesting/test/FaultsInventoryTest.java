package io.quarkiverse.resiliencytesting.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.devui.tests.DevUIBuildTimeDataTest;
import io.quarkus.test.QuarkusDevModeTest;
import tools.jackson.databind.JsonNode;

/**
 * The inventory of guarded methods on the Dev UI card: every method guarded by a Fault Tolerance annotation, with the
 * effective values and the injected types a test can target.
 */
public class FaultsInventoryTest extends DevUIBuildTimeDataTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(StubClient.class, StubSecureClient.class, StubClientUser.class, GuardedService.class)
                    .addAsResource(new StringAsset("""
                            quarkus.resiliency-testing.enabled=true
                            quarkus.rest-client.stub.url=http://stub.example:8080/api
                            quarkus.rest-client.secure.url=https://stub.example:8443/api
                            """), "application.properties"));

    public FaultsInventoryTest() {
        super("quarkus-resiliency-testing");
    }

    @Test
    public void inventoryListsGuardedMethodsWithEffectiveValuesAndInjectedTypes() throws Exception {
        JsonNode inventory = getBuildTimeData("inventory");
        JsonNode methods = inventory.get("guardedMethods");
        JsonNode place = byMethod(methods, "place");
        assertEquals("io.quarkiverse.resiliencytesting.test.GuardedService", place.get("class").asText());
        assertEquals(2000, place.get("annotations").get("Timeout").get("value").asInt());
        assertEquals("MILLIS", place.get("annotations").get("Timeout").get("unit").asText());
        assertEquals("later", place.get("annotations").get("Fallback").get("fallbackMethod").asText());
        assertEquals(2, place.get("annotations").get("Retry").get("maxRetries").asInt());
        assertEquals(0, place.get("annotations").get("Retry").get("delay").asInt());
        assertEquals("io.quarkiverse.resiliencytesting.test.StubClient", place.get("injects").get(0).asText());
        JsonNode later = byMethod(methods, "later");
        assertEquals(2, later.get("annotations").get("Retry").get("maxRetries").asInt()); // class-level
        assertNull(later.get("annotations").get("Timeout"));
        assertNull(byMethod(methods, "helper"));
        assertNull(byMethod(methods, "util"));
        assertTrue(inventory.get("notes").get(0).asText().contains("MicroProfile Config"));
    }

    @Test
    public void guideAndPlaybookAreServed() throws Exception {
        assertTrue(getBuildTimeData("testWritingGuide").asText().startsWith("# Writing tests with Quarkus Resiliency Testing"));
        assertTrue(getBuildTimeData("agentPlaybook").asText().startsWith("# Resiliency Testing agent playbook"));
    }

    private static JsonNode byMethod(JsonNode methods, String name) {
        for (JsonNode m : methods) {
            if (m.get("method").asText().equals(name)) {
                return m;
            }
        }
        return null;
    }
}
