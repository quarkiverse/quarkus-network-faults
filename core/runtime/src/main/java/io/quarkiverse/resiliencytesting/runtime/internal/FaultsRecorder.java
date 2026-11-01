package io.quarkiverse.resiliencytesting.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.enterprise.util.TypeLiteral;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.ConsequenceCleaner;
import io.quarkiverse.resiliencytesting.spi.EventLoopWatcher;
import io.quarkiverse.resiliencytesting.spi.PoolResolver;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkus.arc.All;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.RuntimeInit;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class FaultsRecorder {

    private final FaultsBuildConfig config;

    public FaultsRecorder(FaultsBuildConfig config) {
        this.config = config;
    }

    @StaticInit
    public void register(List<TargetDescriptor> descriptors, Map<String, String> defaultProperties) {
        FaultsRuntime.registerTargets(descriptors);
        FaultsRuntime.registerDefaultProperties(defaultProperties);
    }

    @RuntimeInit
    public Function<SyntheticCreationalContext<Faults>, Faults> faults(ShutdownContext shutdown) {
        shutdown.addLastShutdownTask(FaultsRuntime::close);
        boolean enabled = config.enabled();
        return new Function<>() {
            @Override
            public Faults apply(SyntheticCreationalContext<Faults> context) {
                List<BeanTargetResolver> resolvers = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<ConsequenceCleaner> cleaners = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<PoolResolver> pools = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<EventLoopWatcher> eventLoopWatchers = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                return new FaultsImpl(enabled, resolvers, cleaners, pools, eventLoopWatchers);
            }
        };
    }
}
