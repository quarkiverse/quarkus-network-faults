package io.quarkiverse.resiliencytesting.runtime.internal.config;

import java.util.Map;

import io.quarkiverse.resiliencytesting.runtime.internal.FaultsRuntime;
import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Registered for the runtime configuration only, and only outside normal launch mode.
 */
public class FaultsConfigBuilder implements ConfigBuilder {

    // Below every user-facing source, the lowest of which is META-INF/microprofile-config.properties at 100: a
    // configured value always wins over a default found at build time.
    private static final int DEFAULTS_ORDINAL = 50;

    @Override
    public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
        Map<String, String> defaults = FaultsRuntime.defaultProperties();
        if (!defaults.isEmpty()) {
            builder.withSources(new PropertiesConfigSource(defaults, "resiliency-testing-defaults", DEFAULTS_ORDINAL));
        }
        return builder.withInterceptors(new FaultsInterceptor());
    }
}
