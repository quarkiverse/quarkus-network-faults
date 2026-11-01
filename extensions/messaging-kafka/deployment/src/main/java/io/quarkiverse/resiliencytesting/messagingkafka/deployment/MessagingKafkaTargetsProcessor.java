package io.quarkiverse.resiliencytesting.messagingkafka.deployment;

import java.util.ArrayList;
import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.messagingkafka.runtime.internal.MessagingKafkaTargets;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;

@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class MessagingKafkaTargetsProcessor {

    @BuildStep
    List<NetworkFaultTargetBuildItem> targets() {
        List<NetworkFaultTargetBuildItem> items = new ArrayList<>();
        for (TargetDescriptor d : MessagingKafkaTargets.DESCRIPTORS) {
            items.add(new NetworkFaultTargetBuildItem(d));
        }
        return items;
    }
}
