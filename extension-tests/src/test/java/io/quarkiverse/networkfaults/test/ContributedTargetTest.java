package io.quarkiverse.networkfaults.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.networkfaults.spi.TargetDescriptor;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.UrlRewriter;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;

public class ContributedTargetTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.network-faults.enabled", "true")
            .overrideConfigKey("acme.billing.url", "http://billing.internal:8443/api")
            .addBuildChainCustomizer(contributeAcmeTarget());

    static Consumer<BuildChainBuilder> contributeAcmeTarget() {
        return builder -> builder.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                context.produce(new NetworkFaultTargetBuildItem(
                        new TargetDescriptor("acme.{name}.url", UrlRewriter.class, TargetName.of("acme", "{name}"))));
            }
        }).produces(NetworkFaultTargetBuildItem.class).build();
    }

    @Inject
    NetworkFaults faults;

    @ConfigProperty(name = "acme.billing.url")
    String billingUrl;

    @Test
    public void contributedPropertyIsProxied() {
        assertTrue(billingUrl.startsWith("http://127.0.0.1:"), billingUrl);
        assertTrue(billingUrl.endsWith("/api"), billingUrl);
        assertFalse(billingUrl.contains("billing.internal"), billingUrl);
        assertTrue(faults.targetNames().contains("acme:billing"), faults.targetNames().toString());
        faults.all().unavailable().end();
    }
}
