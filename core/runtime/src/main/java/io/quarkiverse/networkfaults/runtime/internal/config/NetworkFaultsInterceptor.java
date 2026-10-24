package io.quarkiverse.networkfaults.runtime.internal.config;

import jakarta.annotation.Priority;

import io.quarkiverse.networkfaults.runtime.internal.NetworkFaultsRuntime;
import io.quarkiverse.networkfaults.spi.AddressRewriter;
import io.quarkiverse.networkfaults.spi.TargetDescriptor;
import io.quarkiverse.networkfaults.spi.Upstream;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

/**
 * Rewrites the resolved value of every recognised endpoint property so the application connects to a proxy. Only
 * registered when the extension is enabled at build time.
 * Runs outermost in the interceptor chain, above SmallRye's profile, expression and fallback interceptors, so it
 * rewrites the profile-resolved and expanded value the application gets.
 * Address properties the application leaves to an annotation default get their value from the low-ordinal
 * config source {@link NetworkFaultsConfigBuilder} registers, so they take this same path.
 */
@Priority(Priorities.APPLICATION + 500)
public class NetworkFaultsInterceptor implements ConfigSourceInterceptor {

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue configValue = context.proceed(name);
        if (configValue == null || configValue.getValue() == null || configValue.getValue().isBlank()) {
            return configValue;
        }
        TargetCatalog.Match match = NetworkFaultsRuntime.catalog().match(name);
        if (match == null) {
            return configValue;
        }
        return configValue.withValue(NetworkFaultsRuntime.rewrite(match, configValue.getValue(),
                new AddressRewriterContext(name, match, context)));
    }

    private record AddressRewriterContext(String name, TargetCatalog.Match match,
            ConfigSourceInterceptorContext context) implements AddressRewriter.Context {

        @Override
        public String property() {
            return name;
        }

        @Override
        public int proxyPort(Upstream upstream) {
            String portStr = neighbour(TargetDescriptor.RESERVED_PORT_PREFIX + match.property());
            int port = portStr != null ? Integer.parseInt(portStr) : 0;
            return NetworkFaultsRuntime.proxyPort(match, upstream, port);
        }

        @Override
        public String neighbour(String property) {
            ConfigValue other = context.proceed(property);
            return other == null ? null : other.getValue();
        }
    }
}
