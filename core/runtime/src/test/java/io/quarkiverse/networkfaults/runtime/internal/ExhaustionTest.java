package io.quarkiverse.networkfaults.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.Target;
import io.quarkiverse.networkfaults.spi.BeanTargetResolver;
import io.quarkiverse.networkfaults.spi.ConsequenceCleaner;
import io.quarkiverse.networkfaults.spi.EventLoopWatcher;
import io.quarkiverse.networkfaults.spi.Exhaustion;
import io.quarkiverse.networkfaults.spi.PoolResolver;
import io.quarkiverse.networkfaults.spi.ResourceTarget;
import io.quarkiverse.networkfaults.spi.TargetBean;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.Upstream;

class ExhaustionTest {

    private static final String DATASOURCE = "datasource:<default>:jdbc";

    private interface StubClient {
    }

    /**
     * A pool the test holds by hand: nothing can actually wait on it, so whether something did is the test's to say.
     */
    private static final class FakePool implements ResourceTarget {
        private final String name;
        private boolean held;
        private boolean watched = true;
        private boolean contended = true;
        private boolean failNext;
        private boolean failRelease;

        private FakePool() {
            this(DATASOURCE);
        }

        private FakePool(String name) {
            this.name = name;
        }

        @Override
        public Exhaustion exhaust() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("could not be taken whole");
            }
            held = true;
            return new Exhaustion() {
                @Override
                public void release() {
                    held = false;
                    if (failRelease) {
                        throw new IllegalStateException("release of " + name + " failed");
                    }
                }

                @Override
                public boolean watched() {
                    return watched;
                }

                @Override
                public boolean contended() {
                    return contended;
                }
            };
        }
    }

    private static final BeanTargetResolver RESOLVER = new BeanTargetResolver() {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (bean.hasType(DataSource.class)) {
                return Optional.of(TargetName.parse(DATASOURCE));
            }
            if (bean.hasType(StubClient.class)) {
                return Optional.of(TargetName.of("rest-client", "stub"));
            }
            return Optional.empty();
        }
    };

    private final FakePool fake = new FakePool();
    private final AtomicInteger cleanups = new AtomicInteger();
    private final ConsequenceCleaner cleaner = cleanups::incrementAndGet;
    private final FaultTracker tracker = new FaultTracker(List.of(cleaner));

    @AfterEach
    void closeProxies() {
        NetworkFaultsRuntime.close();
    }

    private PoolResolver fakePool() {
        return new PoolResolver() {
            @Override
            public Optional<ResourceTarget> resolve(TargetName target) {
                return target.equals(TargetName.parse(DATASOURCE)) ? Optional.of(fake) : Optional.empty();
            }
        };
    }

    private NetworkFaultsImpl disabled(PoolResolver... pools) {
        return new NetworkFaultsImpl(false, List.of(RESOLVER), List.of(cleaner), List.of(pools), List.of());
    }

    private NetworkFaultsImpl enabled(PoolResolver... pools) {
        return new NetworkFaultsImpl(true, List.of(RESOLVER), List.of(cleaner), List.of(pools), List.of());
    }

    private Target proxiedPool(NetworkFaultsImpl faults) {
        NetworkFaultsRuntime.registry().targetFor(TargetName.parse(DATASOURCE), Upstream.of("localhost", 1), "ds.url");
        return faults.target(DataSource.class);
    }

    private ActiveFault exhausted(String name) {
        return tracker.trackExhaustion(name, new FakePool());
    }

    private ActiveFault networkFaultOn(String name) {
        return TestTargets.guarded(tracker, NetworkFaultsRuntime.registry().targetFor(TargetName.of("rest-client", name),
                Upstream.of("localhost", 1), name + ".url")).unavailable();
    }

    private int cleanerRuns() {
        return cleanups.get();
    }

    @Test
    void exhaustedWorksWhileFaultInjectionIsOff() {
        NetworkFaultsImpl faults = disabled(fakePool());
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            assertTrue(fake.held);
        }
        assertFalse(fake.held);
    }

    @Test
    void aTargetWhileFaultInjectionIsOffSaysHowToEnableIt() {
        NetworkFaultsImpl faults = disabled(fakePool());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> faults.target(DataSource.class));
        assertTrue(e.getMessage().contains("NetworkFaultsProfile"), e.getMessage());
    }

    @Test
    void aResolverThatCannotNameItsTargetStillSaysHowToEnableIt() {
        BeanTargetResolver refusing = new BeanTargetResolver() {
            @Override
            public Optional<TargetName> resolve(TargetBean bean) {
                throw new IllegalArgumentException("pass a qualifier");
            }
        };
        NetworkFaultsImpl faults = new NetworkFaultsImpl(false, List.of(refusing), List.of(), List.of(), List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> faults.target(DataSource.class));
        assertTrue(e.getMessage().contains("NetworkFaultsProfile"), e.getMessage());
    }

    @Test
    void aPoolWithoutAProxyStillExhaustsWhileEnabledButItsTargetTakesNoNetworkFault() {
        NetworkFaultsImpl faults = enabled(fakePool());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> faults.target(DataSource.class));
        assertTrue(e.getMessage().contains("No proxied connection found"), e.getMessage());
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            assertTrue(fake.held);
        }
    }

    @Test
    void aBeanWithAProxyAndAPoolIsBothATargetAndAPool() {
        NetworkFaultsRuntime.registry().targetFor(TargetName.parse(DATASOURCE), Upstream.of("localhost", 1), "ds.url");
        NetworkFaultsImpl faults = enabled(fakePool());
        assertEquals(DATASOURCE, faults.target(DataSource.class).name());
        assertEquals(DATASOURCE, faults.pool(DataSource.class).name());
        faults.target(DataSource.class).unavailable().end();
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            assertTrue(fake.held);
        }
    }

    @Test
    void aBeanWithoutAPoolNamesWhatIsSupported() {
        NetworkFaultsImpl faults = enabled();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> faults.pool(StubClient.class));
        assertTrue(e.getMessage().contains("DataSource"), e.getMessage());
        assertTrue(e.getMessage().contains("workerPool()"), e.getMessage());
    }

    @Test
    void aBeanNoResolverKnowsHasNoPool() {
        NetworkFaultsImpl faults = disabled(fakePool());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> faults.pool(Runnable.class));
        assertTrue(e.getMessage().contains("no pool to exhaust"), e.getMessage());
    }

    @Test
    void aSecondExhaustionIsRefused() {
        NetworkFaultsImpl faults = disabled(fakePool());
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> faults.pool(DataSource.class).exhausted());
            assertTrue(e.getMessage().contains("already"), e.getMessage());
            assertTrue(fake.held, "the refused exhaustion must leave the first one in place");
        }
    }

    @Test
    void aFaultNothingWaitedOnIsAGuardViolation() {
        NetworkFaultsImpl faults = disabled(fakePool());
        fake.contended = false;
        faults.pool(DataSource.class).exhausted().end();
        List<String> violations = faults.takeGuardViolations();
        assertEquals(1, violations.size());
        assertTrue(violations.getFirst().contains(DATASOURCE), violations.getFirst());
        assertTrue(faults.takeGuardViolations().isEmpty());
    }

    @Test
    void aFaultSomethingWaitedOnIsNoViolation() {
        NetworkFaultsImpl faults = disabled(fakePool());
        faults.pool(DataSource.class).exhausted().end();
        assertTrue(faults.takeGuardViolations().isEmpty());
    }

    @Test
    void aFaultNothingCouldWatchIsNotJudged() {
        NetworkFaultsImpl faults = disabled(fakePool());
        List<FaultEnd> ended = new ArrayList<>();
        faults.addFaultListener(new FaultListener() {
            @Override
            public void started(FaultRecord record) {
            }

            @Override
            public void ended(FaultRecord record, FaultEnd end) {
                ended.add(end);
            }
        });
        fake.watched = false;
        fake.contended = false;
        faults.pool(DataSource.class).exhausted().end();
        assertTrue(faults.takeGuardViolations().isEmpty());
        assertEquals(FaultEnd.Verdict.NOT_JUDGED, ended.getFirst().verdict());
    }

    @Test
    void cleanersRunOnceWhenAnExhaustionEnds() {
        var fault = disabled(fakePool()).pool(DataSource.class).exhausted();
        fault.end();
        fault.end();
        assertEquals(1, cleanups.get());
    }

    @Test
    void anExhaustionThatCannotBeTakenLeavesTheTargetFree() {
        NetworkFaultsImpl faults = disabled(fakePool());
        fake.failNext = true;
        assertThrows(IllegalStateException.class, () -> faults.pool(DataSource.class).exhausted());
        assertEquals(0, faults.activeFaultCount());
        faults.pool(DataSource.class).exhausted().end();
    }

    @Test
    void resetAllEndsExhaustions() {
        NetworkFaultsImpl faults = disabled(fakePool());
        faults.pool(DataSource.class).exhausted();
        assertEquals(1, faults.activeFaultCount());
        faults.resetAll();
        assertFalse(fake.held);
        assertEquals(0, faults.activeFaultCount());
    }

    @Test
    void listenersSeeAnExhaustion() {
        NetworkFaultsImpl faults = disabled(fakePool());
        List<FaultRecord> started = new ArrayList<>();
        List<FaultEnd> ended = new ArrayList<>();
        faults.addFaultListener(new FaultListener() {
            @Override
            public void started(FaultRecord record) {
                started.add(record);
            }

            @Override
            public void ended(FaultRecord record, FaultEnd end) {
                assertEquals(started.getLast(), record);
                ended.add(end);
            }
        });

        faults.pool(DataSource.class).exhausted().end();
        fake.contended = false;
        faults.pool(DataSource.class).exhausted().end();

        assertEquals(List.of(DATASOURCE), started.getFirst().targets());
        assertEquals("exhausted", started.getFirst().description());
        assertEquals(List.of(FaultEnd.Verdict.SAW_TRAFFIC, FaultEnd.Verdict.NO_TRAFFIC),
                ended.stream().map(FaultEnd::verdict).toList());
        assertEquals(0, ended.getFirst().connectionsSeen());
        assertEquals(0, ended.getFirst().httpRequestsSeen());
    }

    @Test
    void aNetworkFaultOnAnExhaustedTargetIsRefused() {
        NetworkFaultsImpl faults = enabled(fakePool());
        Target target = proxiedPool(faults);
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, target::hangs);
            assertTrue(e.getMessage().contains("exhausted"), e.getMessage());
        }
    }

    @Test
    void exhaustingATargetWithANetworkFaultIsRefused() {
        NetworkFaultsImpl faults = enabled(fakePool());
        Target target = proxiedPool(faults);
        try (var ignored = target.unavailable()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, faults.pool(DataSource.class)::exhausted);
            assertTrue(e.getMessage().contains("network fault"), e.getMessage());
            assertFalse(fake.held, "a refused exhaustion must hold nothing");
        }
    }

    @Test
    void allIsRefusedWhenAMemberIsExhausted() {
        NetworkFaultsImpl faults = enabled(fakePool());
        proxiedPool(faults);
        NetworkFaultsRuntime.registry().targetFor(TargetName.of("rest-client", "stub"), Upstream.of("localhost", 1),
                "stub.url");
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            assertThrows(IllegalStateException.class, () -> faults.all().hangs());
            assertEquals(1, faults.activeFaultCount());
        }
    }

    @Test
    void droppingTheConnectionsOfAnExhaustedTargetIsRefused() {
        NetworkFaultsImpl faults = enabled(fakePool());
        Target target = proxiedPool(faults);
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, target::dropConnections);
            assertTrue(e.getMessage().contains("exhausted"), e.getMessage());
            assertThrows(IllegalStateException.class, faults.all()::dropConnections);
        }
        target.dropConnections();
    }

    @Test
    void cleanupWaitsForTheLastExhaustion() {
        var pool = exhausted("a");
        var other = exhausted("b");
        networkFaultOn("c").end();
        assertEquals(0, cleanerRuns());
        pool.end();
        assertEquals(0, cleanerRuns());
        other.end();
        assertEquals(1, cleanerRuns());
    }

    @Test
    void aFailedReleaseStillRunsCleanersGuardAndListeners() {
        NetworkFaultsImpl faults = disabled(fakePool());
        List<FaultEnd> ended = new ArrayList<>();
        faults.addFaultListener(new FaultListener() {
            @Override
            public void started(FaultRecord record) {
            }

            @Override
            public void ended(FaultRecord record, FaultEnd end) {
                ended.add(end);
            }
        });
        fake.contended = false;
        fake.failRelease = true;

        var fault = faults.pool(DataSource.class).exhausted();
        assertThrows(IllegalStateException.class, fault::end);

        assertEquals(1, cleanerRuns());
        assertEquals(1, faults.takeGuardViolations().size());
        assertEquals(1, ended.size());
        assertEquals(0, faults.activeFaultCount());
    }

    @Test
    void aFailedReleaseOfTheLastExhaustionStillRunsDeferredCleanup() {
        FakePool failing = new FakePool("a");
        failing.failRelease = true;
        var pool = tracker.trackExhaustion("a", failing);
        networkFaultOn("c").end();
        assertEquals(0, cleanerRuns());

        assertThrows(IllegalStateException.class, pool::end);

        assertEquals(1, cleanerRuns());
    }

    @Test
    void resetAllAttemptsEveryFaultThenThrowsTheFirstFailureWithTheOthersSuppressed() {
        FakePool first = new FakePool("first");
        first.failRelease = true;
        FakePool second = new FakePool("second");
        second.failRelease = true;
        PoolResolver pools = target -> Optional.of(target.equals(TargetName.parse(DATASOURCE)) ? first : second);
        NetworkFaultsImpl faults = new NetworkFaultsImpl(false, List.of(RESOLVER), List.of(cleaner), List.of(pools),
                List.of(watcherReporting(List.of("loop-1 blocked"))));
        faults.pool(DataSource.class).exhausted();
        faults.pool(StubClient.class).exhausted();
        faults.eventLoops().watched();

        IllegalStateException e = assertThrows(IllegalStateException.class, faults::resetAll);

        assertEquals("release of first failed", e.getMessage());
        assertEquals(2, e.getSuppressed().length);
        assertEquals("release of second failed", e.getSuppressed()[0].getMessage());
        assertTrue(e.getSuppressed()[1] instanceof AssertionError, e.getSuppressed()[1]::toString);
        assertFalse(first.held);
        assertFalse(second.held);
        assertEquals(0, faults.activeFaultCount());
        // the watch ended too
        assertEquals(1, cleanerRuns());
        faults.eventLoops().watched();
    }

    private NetworkFaultsImpl withWatcher(EventLoopWatcher... watchers) {
        return new NetworkFaultsImpl(true, List.of(RESOLVER), List.of(cleaner), List.of(), List.of(watchers));
    }

    private static EventLoopWatcher watcherReporting(List<String> blocked) {
        return () -> () -> blocked;
    }

    @Test
    void withoutVertxTheEventLoopsCannotBeWatched() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> withWatcher().eventLoops());
        assertTrue(e.getMessage().contains("no Vert.x"), e.getMessage());
    }

    @Test
    void aSecondWatchIsRefused() {
        NetworkFaultsImpl faults = withWatcher(watcherReporting(List.of()));
        try (var ignored = faults.eventLoops().watched()) {
            assertThrows(IllegalStateException.class, () -> faults.eventLoops().watched());
        }
        faults.eventLoops().watched().end();
    }

    @Test
    void aWatchRunsNoCleanersAndDefersNone() {
        NetworkFaultsImpl faults = withWatcher(watcherReporting(List.of()));
        faults.eventLoops().watched().end();
        assertEquals(0, cleanerRuns());

        // a network fault ending during a watch still cleans up
        NetworkFaultsRuntime.registry().targetFor(TargetName.of("rest-client", "stub"), Upstream.of("localhost", 1),
                "stub.url");
        var watch = faults.eventLoops().watched();
        faults.target(StubClient.class).unavailable().end();
        assertEquals(1, cleanerRuns());
        watch.end();
        assertEquals(1, cleanerRuns());
    }

    @Test
    void aWatchThatSawABlockFailsWhenEnded() {
        NetworkFaultsImpl faults = withWatcher(watcherReporting(List.of("loop-1 blocked")));
        var watch = faults.eventLoops().watched();
        AssertionError e = assertThrows(AssertionError.class, watch::end);
        assertTrue(e.getMessage().contains("loop-1 blocked"), e.getMessage());
        // ended even though it failed
        faults.eventLoops().watched();
        assertThrows(AssertionError.class, faults::resetAll);
    }
}
