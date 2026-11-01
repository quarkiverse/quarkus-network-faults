package io.quarkiverse.resiliencytesting.grpc.deployment;

import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.grpc.runtime.internal.GrpcClientTargets;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.grpc.deployment.GrpcClientBuildItem;

@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class GrpcClientTargetsProcessor {

    @BuildStep
    void targets(List<GrpcClientBuildItem> clients, BuildProducer<NetworkFaultTargetBuildItem> targets) {
        for (GrpcClientBuildItem client : clients) {
            for (TargetDescriptor descriptor : GrpcClientTargets.descriptors(client.getClientName())) {
                targets.produce(new NetworkFaultTargetBuildItem(descriptor));
            }
        }
    }
}
