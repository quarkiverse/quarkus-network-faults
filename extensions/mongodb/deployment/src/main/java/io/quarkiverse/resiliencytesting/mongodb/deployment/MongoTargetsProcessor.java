package io.quarkiverse.resiliencytesting.mongodb.deployment;

import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.mongodb.runtime.internal.MongoTargets;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.mongodb.deployment.spi.MongoClientBuildItem;

/**
 * One target per MongoDB client the application uses, from the MongoDB extension's own build items.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class MongoTargetsProcessor {

    @BuildStep
    void targets(List<MongoClientBuildItem> clients, BuildProducer<NetworkFaultTargetBuildItem> targets) {
        for (MongoClientBuildItem client : clients) {
            for (TargetDescriptor descriptor : MongoTargets.descriptors(client.getName(), client.isDefault())) {
                targets.produce(new NetworkFaultTargetBuildItem(descriptor));
            }
        }
    }
}
