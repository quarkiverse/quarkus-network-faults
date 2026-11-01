package io.quarkiverse.resiliencytesting.deployment.spi;

import java.util.function.BooleanSupplier;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkiverse.resiliencytesting.Faults;

/**
 * Whether fault injection is on, for the {@code onlyIf} of the build steps that declare targets. It reads the flag
 * itself rather than through the core's build configuration mapping, which lives in the runtime module this SPI
 * does not depend on.
 */
public final class NetworkFaultsEnabled implements BooleanSupplier {

    @Override
    public boolean getAsBoolean() {
        return ConfigProvider.getConfig().getOptionalValue(Faults.ENABLED_PROPERTY, Boolean.class).orElse(false);
    }
}
