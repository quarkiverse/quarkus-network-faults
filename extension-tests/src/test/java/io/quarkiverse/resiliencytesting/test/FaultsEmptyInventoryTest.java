package io.quarkiverse.resiliencytesting.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The inventory of an application with no guarded method: an empty list, with a note saying so rather than nothing.
 */
public class FaultsEmptyInventoryTest extends DevUIBuildTimeDataTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.resiliency-testing.enabled=true\n"), "application.properties"));

    public FaultsEmptyInventoryTest() {
        super("quarkus-resiliency-testing");
    }

    @Test
    public void noGuardedMethodIsAnEmptyListWithANote() throws Exception {
        JsonNode inventory = getBuildTimeData("inventory");
        assertEquals(0, inventory.get("guardedMethods").size());
        assertTrue(inventory.get("notes").get(0).asText().contains("No method"));
    }
}
