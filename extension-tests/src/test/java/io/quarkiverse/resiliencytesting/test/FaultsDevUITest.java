package io.quarkiverse.resiliencytesting.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
public class FaultsDevUITest extends DevUIJsonRPCTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(StubClient.class, StubSecureClient.class, StubClientUser.class)
                    .addAsResource(new StringAsset("""
                            quarkus.resiliency-testing.enabled=true
                            quarkus.rest-client.stub.url=http://stub.example:8080/api
                            quarkus.rest-client.secure.url=https://stub.example:8443/api
                            """), "application.properties"));

    public FaultsDevUITest() {
        super("quarkus-resiliency-testing");
    }

    @Test
    public void targetsAreListedAndFaultsToggle() throws Exception {
        JsonNode status = executeJsonRPCMethod("getStatus");
        assertTrue(status.get("enabled").asBoolean(), status.toString());
        JsonNode targets = status.get("targets");
        assertEquals(2, targets.size(), targets.toString());
        JsonNode t = byName(targets, "rest-client:stub");
        assertEquals("rest-client:stub", t.get("name").asText());
        assertEquals("stub.example:8080", t.get("upstream").asText());
        assertEquals("quarkus.rest-client.stub.url", t.get("sourceProperty").asText());
        assertEquals("none", t.get("fault").asText());

        JsonNode applied = executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs"));
        assertTrue(applied.asText().contains("hangs"), applied.toString());
        assertEquals("hangs", byName(executeJsonRPCMethod("getTargets"), "rest-client:stub").get("fault").asText());

        executeJsonRPCMethod("reset", Map.of("target", "rest-client:stub"));
        assertEquals("none", byName(executeJsonRPCMethod("getTargets"), "rest-client:stub").get("fault").asText());

        JsonNode events = executeJsonRPCMethod("getEvents", Map.of("target", "rest-client:stub"));
        assertTrue(events.size() >= 3, events.toString()); // bound, fault started, fault ended
        assertTrue(executeJsonRPCMethod("getFaults").size() > 3);
    }

    @Test
    public void targetsSayWhetherHttpFaultsApply() throws Exception {
        JsonNode targets = executeJsonRPCMethod("getTargets");
        JsonNode stub = byName(targets, "rest-client:stub");
        assertEquals("rest-client", stub.get("kind").asText());
        assertTrue(stub.get("plaintextHttp").asBoolean());
        JsonNode secure = byName(targets, "rest-client:secure");
        assertFalse(secure.get("plaintextHttp").asBoolean());
        JsonNode requests = executeJsonRPCMethod("getRequests", Map.of("target", "rest-client:secure"));
        assertEquals(0, requests.get("requests").size());
        assertTrue(requests.get("reason").asText().contains("quarkus.rest-client.secure.url"));
        assertEquals(0, executeJsonRPCMethod("getRequests", Map.of("target", "rest-client:stub")).get("requests").size());
    }

    @Test
    public void applyTakesAScopeAndStatusCountsActiveFaults() throws Exception {
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "slow:2s", "requests", "GET /api"));
        JsonNode status = executeJsonRPCMethod("getStatus");
        assertEquals(1, status.get("faultsActive").asInt());
        assertTrue(status.get("hint").asText().contains("testWritingGuide"));

        // with a scope nothing is ended, so faults on other requests add up
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs", "requests", "POST /api"));
        assertEquals(2, executeJsonRPCMethod("getStatus").get("faultsActive").asInt());
        // without one the target's faults are ended first
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs"));
        assertEquals(1, executeJsonRPCMethod("getStatus").get("faultsActive").asInt());

        executeJsonRPCMethod("reset", Map.of("target", "rest-client:stub"));
        assertEquals(0, executeJsonRPCMethod("getStatus").get("faultsActive").asInt());
        JsonNode faults = executeJsonRPCMethod("getFaults");
        assertTrue(faults.get(0).has("meaning"), faults.toString());
    }

    @Test
    public void historyFollowsFaultsFromTheCardAndFromTheApi() throws Exception {
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs", "requests", "GET /api"));
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "slow:1s", "requests", "POST /api"));
        executeJsonRPCMethod("resetAll");
        JsonNode history = executeJsonRPCMethod("getHistory");
        assertTrue(history.size() >= 2, history.toString());
        JsonNode newest = history.get(0);
        assertEquals("card", newest.get("source").asText());
        assertEquals("slow:1s", newest.get("spec").asText());
        assertEquals("POST /api", newest.get("requests").asText());
        assertFalse(newest.get("ended").isNull(), newest.toString());
        assertEquals("no-traffic", newest.get("verdict").asText());
        JsonNode report = executeJsonRPCMethod("getReport");
        assertTrue(report.get("markdown").asText().contains("rest-client:stub"));

        // responds-with hands back a response to refine rather than the fault itself; it is recorded from the card too
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "responds-with:503",
                "headers", "Retry-After: 30"));
        JsonNode response = executeJsonRPCMethod("getHistory").get(0);
        assertEquals("card", response.get("source").asText(), response.toString());
        assertEquals("responds-with:503", response.get("spec").asText(), response.toString());
        executeJsonRPCMethod("resetAll");
    }

    @Test
    public void faultsCarryTheirFlagsAndTargetsTheirLastFaultAndReason() throws Exception {
        JsonNode faults = executeJsonRPCMethod("getFaults");
        JsonNode hangs = bySpec(faults, "hangs");
        assertFalse(hangs.get("readsHttp").asBoolean());
        assertFalse(hangs.get("respondsWith").asBoolean());
        JsonNode headers = bySpec(faults, "dies-mid-request:headers");
        assertTrue(headers.get("readsHttp").asBoolean());
        assertFalse(headers.get("respondsWith").asBoolean());
        JsonNode respondsWith = bySpec(faults, "responds-with:503");
        assertTrue(respondsWith.get("readsHttp").asBoolean());
        assertTrue(respondsWith.get("respondsWith").asBoolean());

        // a target that is not plaintext HTTP says why, one that is says nothing
        JsonNode targets = executeJsonRPCMethod("getTargets");
        assertTrue(byName(targets, "rest-client:secure").get("notHttpReason").asText()
                .contains("quarkus.rest-client.secure.url"));
        assertTrue(byName(targets, "rest-client:stub").get("notHttpReason").isNull());

        // once a fault has ended, its target carries it with the guard's verdict: nothing called the stub here
        executeJsonRPCMethod("apply", Map.of("target", "rest-client:stub", "fault", "hangs"));
        executeJsonRPCMethod("reset", Map.of("target", "rest-client:stub"));
        JsonNode last = byName(executeJsonRPCMethod("getTargets"), "rest-client:stub").get("lastFault");
        assertEquals("hangs", last.get("fault").asText(), last.toString());
        assertEquals("no-traffic", last.get("verdict").asText(), last.toString());
        assertEquals(0, last.get("connectionsSeen").asInt());

        // dropping connections is a one-shot that leaves no fault behind and shows up in the events
        assertTrue(executeJsonRPCMethod("dropConnections", Map.of("target", "rest-client:stub")).asText()
                .contains("rest-client:stub"));
        assertEquals("none", byName(executeJsonRPCMethod("getTargets"), "rest-client:stub").get("fault").asText());
        JsonNode events = executeJsonRPCMethod("getEvents", Map.of("target", "rest-client:stub"));
        assertTrue(hasType(events, "FAULT_STARTED"), events.toString());
        assertTrue(hasType(events, "FAULT_ENDED"), events.toString());
        assertTrue(hasType(events, "CONNECTIONS_DROPPED"), events.toString());
        assertTrue(events.get(0).has("time") && events.get(0).has("detail"), events.toString());
    }

    private static JsonNode bySpec(JsonNode faults, String spec) {
        for (JsonNode f : faults) {
            if (f.get("spec").asText().equals(spec)) {
                return f;
            }
        }
        throw new AssertionError("no fault " + spec + " in " + faults);
    }

    private static boolean hasType(JsonNode events, String type) {
        for (JsonNode e : events) {
            if (e.get("type").asText().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode byName(JsonNode targets, String name) {
        for (JsonNode t : targets) {
            if (t.get("name").asText().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("no target " + name + " in " + targets);
    }
}
