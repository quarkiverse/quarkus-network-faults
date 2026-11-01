package io.quarkiverse.resiliencytesting.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.test.QuarkusDevModeTest;
import tools.jackson.databind.JsonNode;

/**
 * The Dev UI card of an application with fault injection on but no dependency to proxy yet: it says it is on, with no
 * targets, rather than asking for the property that is already set.
 */
public class FaultsDevUIWithoutTargetsTest extends DevUIJsonRPCTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.resiliency-testing.enabled=true\n"), "application.properties"));

    public FaultsDevUIWithoutTargetsTest() {
        super("quarkus-resiliency-testing");
    }

    @Test
    public void enabledWithoutTargets() throws Exception {
        JsonNode status = executeJsonRPCMethod("getStatus");
        assertTrue(status.get("enabled").asBoolean(), status.toString());
        assertEquals(0, status.get("targets").size(), status.toString());
    }
}
