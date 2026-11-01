package io.quarkiverse.resiliencytesting.reactivedatasource.deployment;

import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal.ReactiveDatasourceTargets;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.reactive.datasource.spi.ReactiveDataSourceBuildItem;

/**
 * One target per datasource a reactive client configured, from the reactive datasource SPI build items.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class ReactiveDatasourceTargetsProcessor {

    @BuildStep
    void targets(List<ReactiveDataSourceBuildItem> dataSources, BuildProducer<NetworkFaultTargetBuildItem> targets) {
        for (ReactiveDataSourceBuildItem ds : dataSources) {
            targets.produce(new NetworkFaultTargetBuildItem(
                    ReactiveDatasourceTargets.descriptor(ds.getName(), ds.isDefault())));
        }
    }
}
