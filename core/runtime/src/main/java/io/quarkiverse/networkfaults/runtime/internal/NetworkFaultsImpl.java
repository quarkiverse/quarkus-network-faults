package io.quarkiverse.networkfaults.runtime.internal;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.inject.Instance;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.Endpoint;
import io.quarkiverse.networkfaults.EventLoops;
import io.quarkiverse.networkfaults.Exhaustible;
import io.quarkiverse.networkfaults.MethodReference;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyRegistry;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget;
import io.quarkiverse.networkfaults.spi.BeanTargetResolver;
import io.quarkiverse.networkfaults.spi.ConsequenceCleaner;
import io.quarkiverse.networkfaults.spi.EventLoopWatcher;
import io.quarkiverse.networkfaults.spi.PoolResolver;
import io.quarkiverse.networkfaults.spi.ResourceTarget;
import io.quarkiverse.networkfaults.spi.TargetBean;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.TargetRequests;
import io.quarkus.runtime.LaunchMode;

/**
 * The injectable {@link NetworkFaults} bean. Targets are looked up by the names the declaring extensions gave them.
 * Every target handed out is tracked, so faults are cleaned up after and judged by the no-traffic guard.
 */
public final class NetworkFaultsImpl implements NetworkFaults {

    private final boolean enabled;
    private final List<BeanTargetResolver> resolvers;
    private final List<PoolResolver> pools;
    private final List<EventLoopWatcher> eventLoopWatchers;
    private final FaultTracker tracker;
    // the open event-loop watch: not a fault, so apart from the tracker, which cleans up after faults
    private final AtomicReference<ActiveFault> eventLoopWatch = new AtomicReference<>();

    public NetworkFaultsImpl(boolean enabled, List<BeanTargetResolver> resolvers, List<ConsequenceCleaner> cleaners,
            List<PoolResolver> pools, List<EventLoopWatcher> eventLoopWatchers) {
        this.enabled = enabled;
        this.resolvers = resolvers;
        this.pools = pools;
        this.eventLoopWatchers = eventLoopWatchers;
        this.tracker = new FaultTracker(cleaners);
    }

    /**
     * Whether fault injection is on, whether or not a target exists yet. Not part of the API: the Dev UI uses it.
     */
    public boolean isEnabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw notEnabled();
        }
    }

    private RuntimeException notEnabled() {
        if (LaunchMode.current().isDev()) {
            return new IllegalStateException("Network fault injection is not enabled. Ensure that "
                    + ENABLED_PROPERTY
                    + "=true is set.");
        }
        return new IllegalStateException("Network fault injection is not enabled for this test. Annotate the test "
                + "class with @TestProfile(NetworkFaultsProfile.class), or set "
                + ENABLED_PROPERTY
                + "=true in your own test profile.");
    }

    private ProxyRegistry registry() {
        requireEnabled();
        return NetworkFaultsRuntime.registry();
    }

    /**
     * Targets to report on: none while disabled or before the first one exists.
     */
    private Collection<ProxyTarget> liveTargets() {
        return enabled && NetworkFaultsRuntime.isStarted() ? NetworkFaultsRuntime.registry().targets() : List.of();
    }

    private RuntimeException noProxiedConnection(String name) {
        return new IllegalArgumentException("No proxied connection found for '" + name + "'. Known targets: "
                + targetNames() + ". Targets appear once their configuration has been read by the application; "
                + "check that the dependency is configured and that the test class uses "
                + "@TestProfile(NetworkFaultsProfile.class).");
    }

    private ObservableTarget tracked(String name, List<ProxyTarget> members) {
        if (members.isEmpty()) {
            throw noProxiedConnection(name);
        }
        // a single member answers to its own name, whatever selector found it
        return new CompositeTarget(members.size() == 1 ? members.getFirst().name() : name, members, tracker);
    }

    @Override
    public ObservableTarget target(Class<?> type, Annotation... qualifiers) {
        return target(TargetBean.of(type, qualifiers));
    }

    @Override
    public ObservableTarget target(Instance<?> instance) {
        return target(TargetBean.of(instance));
    }

    @Override
    public <B> Endpoint target(MethodReference.Method0<B> method) {
        return endpoint(method);
    }

    @Override
    public <B, P1> Endpoint target(MethodReference.Method1<B, P1> method) {
        return endpoint(method);
    }

    @Override
    public <B, P1, P2> Endpoint target(MethodReference.Method2<B, P1, P2> method) {
        return endpoint(method);
    }

    @Override
    public <B, P1, P2, P3> Endpoint target(MethodReference.Method3<B, P1, P2, P3> method) {
        return endpoint(method);
    }

    @Override
    public <B, P1, P2, P3, P4> Endpoint target(MethodReference.Method4<B, P1, P2, P3, P4> method) {
        return endpoint(method);
    }

    @Override
    public <B, P1, P2, P3, P4, P5> Endpoint target(MethodReference.Method5<B, P1, P2, P3, P4, P5> method) {
        return endpoint(method);
    }

    /**
     * The requests the method a reference names sends, as the resolver that knows its type describes them.
     */
    private Endpoint endpoint(Serializable reference) {
        requireEnabled();
        ReferencedMethod referenced = ReferencedMethod.of(reference);
        for (BeanTargetResolver resolver : resolvers) {
            TargetRequests requests = resolver.requests(referenced.type(), referenced.method()).orElse(null);
            if (requests != null) {
                return target(requests.target()).http().requests(requests.method(), requests.path());
            }
        }
        throw new IllegalArgumentException("No extension recognises " + referenced + " as a method that sends HTTP "
                + "requests; a REST Client method does. Known targets: " + targetNames());
    }

    private ObservableTarget target(TargetBean bean) {
        // which resolvers exist says nothing while fault injection is off: some are only built when it is on
        requireEnabled();
        TargetName name = nameOf(bean);
        if (name == null) {
            throw noResolver(bean);
        }
        return target(name);
    }

    @Override
    public Exhaustible pool(Class<?> type, Annotation... qualifiers) {
        return pool(TargetBean.of(type, qualifiers));
    }

    @Override
    public Exhaustible pool(Instance<?> instance) {
        return pool(TargetBean.of(instance));
    }

    private Exhaustible pool(TargetBean bean) {
        TargetName name = nameOf(bean);
        ResourceTarget resource = name == null ? null : poolOf(name);
        if (resource == null) {
            throw new IllegalArgumentException(bean + " has no pool to exhaust. Supported are the DataSource beans of "
                    + "Agroal and the Pool beans of the reactive SQL clients, with their datasource qualifier, and "
                    + "workerPool() for the worker pool.");
        }
        return new Pool(name.toString(), resource);
    }

    /**
     * A pool as handed to tests, tracked under the name of the target it belongs to.
     */
    private final class Pool implements Exhaustible {
        private final String name;
        private final ResourceTarget resource;

        private Pool(String name, ResourceTarget resource) {
            this.name = name;
            this.resource = resource;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ActiveFault exhausted() {
            return tracker.trackExhaustion(name, resource);
        }
    }

    private TargetName nameOf(TargetBean bean) {
        for (BeanTargetResolver resolver : resolvers) {
            TargetName name = resolver.resolve(bean).orElse(null);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private ResourceTarget poolOf(TargetName name) {
        for (PoolResolver pool : pools) {
            ResourceTarget resource = pool.resolve(name).orElse(null);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    private RuntimeException noResolver(TargetBean bean) {
        List<String> present = new ArrayList<>();
        for (BeanTargetResolver resolver : resolvers) {
            present.add(resolver.getClass().getName());
        }
        return new IllegalArgumentException("No extension recognises " + bean + " as a proxied connection (resolvers "
                + "present: " + present + "). Known targets: " + targetNames());
    }

    /**
     * The target with this name, or every target the name covers; {@code all} is every one. Not part of the
     * API: the Dev UI uses it.
     */
    public ObservableTarget byName(String name) {
        return target(TargetName.parse(name));
    }

    private ObservableTarget target(TargetName selector) {
        Map<String, ProxyTarget> members = new TreeMap<>();
        for (ProxyTarget t : registry().targets()) {
            if (selector.covers(t.targetName())) {
                members.put(t.name(), t);
            }
        }
        return tracked(selector.toString(), new ArrayList<>(members.values()));
    }

    @Override
    public Exhaustible workerPool() {
        return new Pool(WorkerPool.NAME, new WorkerPool());
    }

    @Override
    public EventLoops eventLoops() {
        if (eventLoopWatchers.isEmpty()) {
            throw new IllegalStateException("The event loops cannot be watched: the application has no Vert.x");
        }
        return () -> {
            // before the watcher is asked: starting a second watch would replace the first one's handler
            if (eventLoopWatch.get() != null) {
                throw alreadyWatched();
            }
            EventLoopWatch watch = new EventLoopWatch(eventLoopWatchers.getFirst().watch());
            if (!eventLoopWatch.compareAndSet(null, watch)) {
                watch.stopWatching();
                throw alreadyWatched();
            }
            return watch;
        };
    }

    private static IllegalStateException alreadyWatched() {
        return new IllegalStateException("The event loops are already watched; end that watch before starting"
                + " another");
    }

    /**
     * Ending the watch stops the watcher and fails if an event loop was blocked meanwhile.
     */
    private final class EventLoopWatch implements ActiveFault {

        private final EventLoopWatcher.Watch watch;

        EventLoopWatch(EventLoopWatcher.Watch watch) {
            this.watch = watch;
        }

        void stopWatching() {
            watch.stop();
        }

        @Override
        public void end() {
            if (!eventLoopWatch.compareAndSet(this, null)) {
                return;
            }
            List<String> blocked = watch.stop();
            if (!blocked.isEmpty()) {
                throw new AssertionError("An event loop was blocked while watched:\n  "
                        + String.join("\n  ", blocked));
            }
        }
    }

    @Override
    public ObservableTarget all() {
        return target(TargetName.ALL);
    }

    @Override
    public Set<String> targetNames() {
        Set<String> names = new TreeSet<>();
        for (ProxyTarget t : liveTargets()) {
            names.add(t.name());
        }
        return names;
    }

    public List<TargetSnapshot> snapshot() {
        Map<String, TargetSnapshot> byName = new TreeMap<>();
        for (ProxyTarget t : liveTargets()) {
            byName.put(t.name(), t.snapshot());
        }
        return new ArrayList<>(byName.values());
    }

    @Override
    public void resetAll() {
        List<ActiveFault> ends = new ArrayList<>();
        ends.add(tracker::endAll);
        ActiveFault watch = eventLoopWatch.get();
        if (watch != null) {
            ends.add(watch);
        }
        FaultTracker.endEach(ends);
    }

    /**
     * The record of a fault this instance handed out, as its listeners were told of it: the handle itself, or, for
     * {@code respondsWith}, the response that wraps it. Null for a handle that is not one of ours. Not part of the
     * API: the Dev UI uses it to mark what it applied.
     */
    public FaultRecord recordOf(ActiveFault handed) {
        ActiveFault fault = handed instanceof HttpResponseConfig response ? response.applied() : handed;
        return fault instanceof FaultTracker.Group group ? group.record() : null;
    }

    /**
     * Tells {@code listener} of every fault handed out that starts or ends from now on. Not part of the API: the
     * Dev UI uses it.
     */
    public void addFaultListener(FaultListener listener) {
        tracker.addListener(listener);
    }

    /**
     * How many faults handed out are active right now. Not part of the API: the Dev UI uses it.
     */
    public int activeFaultCount() {
        return tracker.activeCount();
    }

    /**
     * The no-traffic guard's verdicts since the last call, one message per fault that was active on a target no
     * connection went through, cleared on reading. The JUnit after-each callback reads them once every test and
     * fails the test with them; see {@link FaultTracker} for why the guard records instead of throwing.
     */
    public List<String> takeGuardViolations() {
        return tracker.takeViolations();
    }
}
