package io.quarkiverse.resiliencytesting.langchain4j.mcp.runtime.internal;

import java.util.Optional;
import java.util.Set;

import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.UrlRewriter;

/**
 * The MCP servers the clients of Quarkus LangChain4j talk to. A client over the streamable HTTP or the WebSocket
 * transport has a {@code quarkus.langchain4j.mcp.<client>.url}, which becomes the target {@code mcp:<client>}; a
 * client over stdio runs a process and has no connection to fault.
 */
public final class McpTargets {

    private static final String FAMILY = "mcp";

    private McpTargets() {
    }

    public static TargetDescriptor descriptor(String client) {
        return new TargetDescriptor("quarkus.langchain4j.mcp." + TargetDescriptor.propertySegment(client) + ".url",
                UrlRewriter.class, name(client));
    }

    static TargetName name(String client) {
        return TargetName.of(FAMILY, client);
    }

    /**
     * An {@code McpClient} bean is the client its {@code @McpClientName} names; every MCP client has one.
     */
    public static final class Resolver implements BeanTargetResolver {

        // the clients with a URL
        private final Set<String> clients;

        public Resolver(Set<String> clients) {
            this.clients = Set.copyOf(clients);
        }

        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (!bean.hasType(McpClient.class)) {
                return Optional.empty();
            }
            Optional<McpClientName> qualifier = bean.qualifier(McpClientName.class);
            if (qualifier.isEmpty() || qualifier.get().value().isBlank()) {
                throw new IllegalArgumentException("Every MCP client has a name: pass McpClientName.Literal.of(name); "
                        + "the clients over HTTP or WebSocket: " + clients);
            }
            String client = qualifier.get().value();
            if (!clients.contains(client)) {
                throw new IllegalArgumentException("MCP client '" + client + "' has no URL to proxy: it is not "
                        + "configured, it is disabled, or it uses the stdio transport; the clients over HTTP or "
                        + "WebSocket: " + clients);
            }
            return Optional.of(name(client));
        }
    }
}
