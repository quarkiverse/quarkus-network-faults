package io.quarkiverse.resiliencytesting.datahibernate.runtime.internal;

import java.util.Map;

import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class DataRepositoryTargetsRecorder {

    /**
     * The resolver bean, wrapped since a final class cannot be proxied, made with the repository targets the build computed.
     */
    @StaticInit
    public RuntimeValue<DataRepositoryTargets.Resolver> resolver(Map<String, TargetName> repositories) {
        return new RuntimeValue<>(new DataRepositoryTargets.Resolver(repositories));
    }
}
