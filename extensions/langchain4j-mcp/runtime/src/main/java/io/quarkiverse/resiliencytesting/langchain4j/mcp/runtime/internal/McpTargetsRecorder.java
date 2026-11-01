package io.quarkiverse.resiliencytesting.langchain4j.mcp.runtime.internal;

import java.util.Set;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class McpTargetsRecorder {

    /**
     * The resolver bean, wrapped since a final class cannot be proxied, made with the clients the build found a URL for.
     */
    @StaticInit
    public RuntimeValue<McpTargets.Resolver> resolver(Set<String> clients) {
        return new RuntimeValue<>(new McpTargets.Resolver(clients));
    }
}
