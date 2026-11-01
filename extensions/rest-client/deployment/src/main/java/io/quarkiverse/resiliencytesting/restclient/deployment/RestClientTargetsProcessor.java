package io.quarkiverse.resiliencytesting.restclient.deployment;

import java.util.ArrayList;
import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultDefaultValueBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.restclient.runtime.internal.RestClientTargets;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.rest.client.reactive.deployment.RegisteredRestClientBuildItem;

/**
 * One target per client the REST Client extension registered. The client resolves its url through its
 * {@code configKey}, its class name or its simple name, so all three properties get the same target name. A
 * {@code baseUri} on the annotation has no property at all, so it is supplied as a default value under the key
 * the client reads first.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class RestClientTargetsProcessor {

    @BuildStep
    void targets(List<RegisteredRestClientBuildItem> clients, BuildProducer<NetworkFaultTargetBuildItem> targets,
            BuildProducer<NetworkFaultDefaultValueBuildItem> defaults) {
        for (RegisteredRestClientBuildItem client : clients) {
            String className = client.getClassInfo().name().toString();
            String simpleName = client.getClassInfo().simpleName();
            TargetName name = RestClientTargets.name(client.getConfigKey().orElse(simpleName));
            List<String> keys = new ArrayList<>();
            if (client.getConfigKey().isPresent()) {
                keys.add(client.getConfigKey().get());
            }
            keys.add(className);
            keys.add(simpleName);
            for (String key : keys) {
                for (String attribute : List.of("url", "uri")) {
                    targets.produce(new NetworkFaultTargetBuildItem(RestClientTargets.descriptor(key, attribute, name)));
                }
            }
            if (client.getDefaultBaseUri().isPresent()) {
                defaults.produce(new NetworkFaultDefaultValueBuildItem(RestClientTargets.property(keys.getFirst(), "url"),
                        client.getDefaultBaseUri().get()));
            }
        }
    }
}
