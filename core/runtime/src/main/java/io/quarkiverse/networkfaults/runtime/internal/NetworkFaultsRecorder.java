package io.quarkiverse.networkfaults.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.enterprise.util.TypeLiteral;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.spi.BeanTargetResolver;
import io.quarkiverse.networkfaults.spi.ConsequenceCleaner;
import io.quarkiverse.networkfaults.spi.EventLoopWatcher;
import io.quarkiverse.networkfaults.spi.PoolResolver;
import io.quarkiverse.networkfaults.spi.TargetDescriptor;
import io.quarkus.arc.All;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.RuntimeInit;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class NetworkFaultsRecorder {

    private final NetworkFaultsBuildConfig config;

    public NetworkFaultsRecorder(NetworkFaultsBuildConfig config) {
        this.config = config;
    }

    @StaticInit
    public void register(List<TargetDescriptor> descriptors, Map<String, String> defaultProperties) {
        NetworkFaultsRuntime.registerTargets(descriptors);
        NetworkFaultsRuntime.registerDefaultProperties(defaultProperties);
    }

    @RuntimeInit
    public Function<SyntheticCreationalContext<NetworkFaults>, NetworkFaults> networkFaults(ShutdownContext shutdown) {
        shutdown.addLastShutdownTask(NetworkFaultsRuntime::close);
        boolean enabled = config.enabled();
        return new Function<>() {
            @Override
            public NetworkFaults apply(SyntheticCreationalContext<NetworkFaults> context) {
                List<BeanTargetResolver> resolvers = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<ConsequenceCleaner> cleaners = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<PoolResolver> pools = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                List<EventLoopWatcher> eventLoopWatchers = context.getInjectedReference(new TypeLiteral<>() {
                }, All.Literal.INSTANCE);
                return new NetworkFaultsImpl(enabled, resolvers, cleaners, pools, eventLoopWatchers);
            }
        };
    }
}
