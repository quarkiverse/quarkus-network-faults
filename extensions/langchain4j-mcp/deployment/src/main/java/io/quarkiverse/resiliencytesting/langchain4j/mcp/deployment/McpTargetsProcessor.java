package io.quarkiverse.resiliencytesting.langchain4j.mcp.deployment;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Singleton;

import io.quarkiverse.langchain4j.mcp.runtime.config.McpBuildTimeConfiguration;
import io.quarkiverse.langchain4j.mcp.runtime.config.McpClientBuildTimeConfig;
import io.quarkiverse.langchain4j.mcp.runtime.config.McpTransportType;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.langchain4j.mcp.runtime.internal.McpTargets;
import io.quarkiverse.resiliencytesting.langchain4j.mcp.runtime.internal.McpTargetsRecorder;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;

/**
 * One target per MCP client that connects over the network, from the MCP extension's build-time configuration, and
 * the resolver bean that knows them.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class McpTargetsProcessor {

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void targets(McpBuildTimeConfiguration mcp, BuildProducer<NetworkFaultTargetBuildItem> targets,
            BuildProducer<SyntheticBeanBuildItem> beans, McpTargetsRecorder recorder) {
        Set<String> clients = new LinkedHashSet<>();
        if (mcp.enabled() && mcp.clients() != null) {
            for (Map.Entry<String, McpClientBuildTimeConfig> client : mcp.clients().entrySet()) {
                // a stdio client runs a process; the extension's own sections under the same prefix (health,
                // tracing, ...) have no transport and read as stdio too
                if (client.getValue().enabled() && client.getValue().transportType() != McpTransportType.STDIO) {
                    clients.add(client.getKey());
                    targets.produce(new NetworkFaultTargetBuildItem(McpTargets.descriptor(client.getKey())));
                }
            }
        }
        beans.produce(SyntheticBeanBuildItem.configure(McpTargets.Resolver.class)
                .addType(BeanTargetResolver.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.resolver(clients))
                .done());
    }
}
