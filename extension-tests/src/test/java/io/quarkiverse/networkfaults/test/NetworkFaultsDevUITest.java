package io.quarkiverse.networkfaults.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.test.QuarkusDevModeTest;
import tools.jackson.databind.JsonNode;

/**
 * The Dev UI JSON-RPC service in dev mode: targets are listed, faults can be applied and ended.
 */
public class NetworkFaultsDevUITest extends DevUIJsonRPCTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(StubClient.class, StubClientUser.class)
                    .addAsResource(new StringAsset("""
                            quarkus.network-faults.enabled=true
                            quarkus.rest-client.stub.url=http://stub.example:8080/api
                            """), "application.properties"));

    public NetworkFaultsDevUITest() {
        super("quarkus-network-faults");
    }

    @Test
    public void targetsAreListedAndFaultsToggle() throws Exception {
        JsonNode status = executeJsonRPCMethod("getStatus");
        assertTrue(status.get("enabled").asBoolean(), status.toString());
        JsonNode targets = status.get("targets");
        assertEquals(1, targets.size(), targets.toString());
        JsonNode t = targets.get(0);
        assertEquals("rest-client:stub", t.get("name").asText());
        assertEquals("stub.example:8080", t.get("upstream").asText());
        assertEquals("quarkus.rest-client.stub.url", t.get("sourceProperty").asText());
        assertEquals("none", t.get("fault").asText());

        JsonNode applied = executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs"));
        assertTrue(applied.asText().contains("hangs"), applied.toString());
        assertEquals("hangs", executeJsonRPCMethod("getTargets").get(0).get("fault").asText());

        executeJsonRPCMethod("reset", Map.of("target", "rest-client:stub"));
        assertEquals("none", executeJsonRPCMethod("getTargets").get(0).get("fault").asText());

        JsonNode events = executeJsonRPCMethod("getEvents", Map.of("target", "rest-client:stub"));
        assertTrue(events.size() >= 3, events.toString()); // bound, fault started, fault ended
        assertTrue(executeJsonRPCMethod("getFaults").size() > 3);
    }
}
