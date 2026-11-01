package io.quarkiverse.resiliencytesting.agroal.deployment;

import java.util.List;

import io.quarkiverse.resiliencytesting.agroal.runtime.internal.AgroalTargets;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;

@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class AgroalTargetsProcessor {

    @BuildStep
    void targets(List<JdbcDataSourceBuildItem> dataSources, BuildProducer<NetworkFaultTargetBuildItem> targets) {
        for (JdbcDataSourceBuildItem ds : dataSources) {
            targets.produce(new NetworkFaultTargetBuildItem(AgroalTargets.descriptor(ds.getName(), ds.isDefault())));
        }
    }
}
