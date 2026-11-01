package io.quarkiverse.resiliencytesting.hibernateorm.runtime.internal;

import java.util.Map;

import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class PersistenceUnitTargetsRecorder {

    /**
     * The resolver bean, wrapped since a final class cannot be proxied, made with the unit-to-datasource maps the build
     * computed.
     */
    @StaticInit
    public RuntimeValue<PersistenceUnitTargets.Resolver> resolver(Map<String, TargetName> blockingUnits,
            Map<String, TargetName> reactiveUnits) {
        return new RuntimeValue<>(new PersistenceUnitTargets.Resolver(blockingUnits, reactiveUnits));
    }
}
