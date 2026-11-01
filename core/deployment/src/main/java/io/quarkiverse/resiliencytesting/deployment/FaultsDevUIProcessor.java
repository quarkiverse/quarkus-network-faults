package io.quarkiverse.resiliencytesting.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import io.quarkiverse.resiliencytesting.dev.FaultHistory;
import io.quarkiverse.resiliencytesting.dev.FaultsJsonRPCService;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.devjsonrpc.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * Dev UI card: live targets, fault toggles, the verdict of each target's last fault, connection table, event log,
 * requests and a report.
 */
@BuildSteps(onlyIf = IsLocalDevelopment.class)
class FaultsDevUIProcessor {

    private static final String INVENTORY_DESCRIPTION = "Where this application believes it is protected: every method"
            + " guarded by a MicroProfile Fault Tolerance annotation, with its effective parameters as declared in the"
            + " code, and the types it injects that a test can pass to target(...). The targets themselves come from"
            + " getTargets. Derive the tests to write from it: one per guarded method and fault.";

    private static final String GUIDE_DESCRIPTION = "Read this before writing or changing a test that uses Quarkus Network"
            + " Faults: the test shape, how to address each kind of target, which fault for which scenario, counting"
            + " calls, the guard, timeouts, and the mistakes to avoid.";

    private static final String PLAYBOOK_DESCRIPTION = "Read this before any network fault experiment: safety rules,"
            + " which fault proves which Fault Tolerance annotation, how to tell the fault was exercised, the hypothesis"
            + " / fault / observation / conclusion loop that ends in a test, and the shape of the report.";

    @BuildStep
    CardPageBuildItem card(ApplicationArchivesBuildItem applicationArchives, CombinedIndexBuildItem combinedIndex) {
        CardPageBuildItem card = new CardPageBuildItem();
        card.addPage(Page.webComponentPageBuilder()
                .icon("font-awesome-solid:plug-circle-xmark")
                .title("Resiliency Testing")
                .componentLink("qwc-resiliency-testing.js"));
        card.addBuildTimeData("inventory", ResilienceInventory.of(applicationArchives.getRootArchive().getIndex(),
                combinedIndex.getIndex()), INVENTORY_DESCRIPTION, true);
        card.addBuildTimeData("testWritingGuide", readResource("resiliency-testing/test-writing-guide.md"),
                GUIDE_DESCRIPTION, true);
        card.addBuildTimeData("agentPlaybook", readResource("resiliency-testing/agent-playbook.md"),
                PLAYBOOK_DESCRIPTION, true);
        return card;
    }

    /**
     * The text of a Markdown document the build copies into this jar from the docs.
     *
     * @throws IllegalStateException if the build did not copy it
     */
    private static String readResource(String path) {
        try (InputStream in = FaultsDevUIProcessor.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is missing from the deployment jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BuildStep
    JsonRPCProvidersBuildItem jsonRpc() {
        return new JsonRPCProvidersBuildItem(FaultsJsonRPCService.class);
    }

    @BuildStep
    AdditionalBeanBuildItem faultHistory() {
        // core/runtime-dev has no beans.xml, so its beans are not discovered
        return AdditionalBeanBuildItem.unremovableOf(FaultHistory.class);
    }
}
