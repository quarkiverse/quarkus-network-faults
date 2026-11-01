package io.quarkiverse.resiliencytesting.lc4jmcp;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * An MCP server behind the proxy, addressed by the client bean that talks to it.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(McpServerStub.class)
public class McpScenariosTest {

    private static final String FORECAST = "/tools/forecast?city=Athens";

    @Inject
    Faults faults;

    @Inject
    @McpClientName("weather")
    Instance<McpClient> weather;

    private Target server() {
        return faults.target(McpClient.class, McpClientName.Literal.of("weather"));
    }

    /**
     * The first call makes the client initialise its session, two requests the tests do not want to count.
     */
    private static void warmUp() {
        when()
                .get("/tools")
                .then()
                .statusCode(200)
                .body(is("forecast"));
    }

    @Test
    public void theClientResolvesByItsName() {
        assertEquals("mcp:weather", server().name());
        assertEquals(server().name(), faults.target(weather).name());
    }

    @Test
    public void aClientWithoutANameIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> faults.target(McpClient.class));
        assertTrue(e.getMessage().contains("McpClientName.Literal.of"), e.getMessage());
    }

    @Test
    public void anUnknownClientIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> faults.target(McpClient.class, McpClientName.Literal.of("calendar")));
        assertTrue(e.getMessage().contains("[weather]"), e.getMessage());
    }

    @Test
    public void toolCallsGoThroughTheProxy() {
        warmUp();
        long before = server().traffic().httpRequestsSeen();
        when()
                .post(FORECAST)
                .then()
                .statusCode(200)
                .body(is("sunny in Athens"));
        assertEquals(1, server().traffic().httpRequestsSeen() - before, server().traffic().httpRequests().toString());
    }

    @Test
    public void theServerIsUnavailable() {
        warmUp();
        try (ActiveFault f = server().unavailable()) {
            when()
                    .post(FORECAST)
                    .then()
                    .statusCode(503);
        }
        when()
                .post(FORECAST)
                .then()
                .statusCode(200)
                .body(is("sunny in Athens"));
    }

    @Test
    public void aHungToolCallIsBoundedByTheToolExecutionTimeout() {
        warmUp();
        try (ActiveFault f = server().hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .post(FORECAST)
                    .then()
                    .statusCode(503)
                    .body(containsStringIgnoringCase("timeout")));
            assertTrue(ms >= 1_900, "the tool call must wait for tool-execution-timeout, took " + ms + "ms");
            assertTrue(ms < 9_000, "tool-execution-timeout must bound the hang, took " + ms + "ms");
        }
        when()
                .post(FORECAST)
                .then()
                .statusCode(200);
    }

    @Test
    public void theServerAnswersWithAnError() {
        warmUp();
        long before = server().traffic().httpRequestsSeen();
        try (ActiveFault f = server().http().respondsWith(500)) {
            when()
                    .post(FORECAST)
                    .then()
                    .statusCode(503);
        }
        assertEquals(1, server().traffic().httpRequestsSeen() - before, server().traffic().httpRequests().toString());
        when()
                .post(FORECAST)
                .then()
                .statusCode(200);
    }
}
