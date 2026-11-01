package io.quarkiverse.resiliencytesting.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.inject.Any;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyRegistry;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class FaultsImplTest {

    @Test
    void duplicateQualifiersDoNotSurfaceAsADuplicateElementError() {
        FaultsImpl faults = new FaultsImpl(true, List.of(), List.of(), List.of(), List.of());
        Any any = Any.Literal.INSTANCE;
        // No resolver matches String here, so resolution still fails - but with a helpful "no extension
        // recognises" message, not the bare "duplicate element" IllegalArgumentException that Set.of(qualifiers)
        // used to raise before reaching resolution.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> faults.target(String.class, any, any));
        assertFalse(e.getMessage().toLowerCase().contains("duplicate"),
                "duplicate qualifiers should not produce a 'duplicate element' error: " + e.getMessage());
    }

    @Test
    void aDisabledInstanceSaysSoBeforeLookingForTheBean() {
        FaultsImpl faults = new FaultsImpl(false, List.of(), List.of(), List.of(), List.of());
        // not "no extension recognises String": which resolvers exist says nothing while fault injection is off
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> faults.target(String.class));
        assertTrue(e.getMessage().contains("not enabled"), e.getMessage());
    }

    @Test
    void resettingATargetEndsEveryFaultCoveringItThroughTheTracker() {
        ProxyRegistry registry = FaultsRuntime.registry();
        try {
            // no connection goes through these proxies, so their upstream is never dialled
            Upstream upstream = Upstream.of("localhost", 1);
            registry.targetFor(TargetName.of("rest-client", "a"), upstream, "a.url");
            registry.targetFor(TargetName.of("rest-client", "b"), upstream, "b.url");
            registry.targetFor(TargetName.of("datasource", "users"), upstream, "users.url");
            FaultsImpl faults = new FaultsImpl(true, List.of(), List.of(), List.of(), List.of());
            faults.byName("rest-client").unavailable(); // one fault, both rest clients
            faults.byName("datasource:users").unavailable();
            assertEquals(2, faults.activeFaultCount());

            // one member covered is enough to end the whole fault
            faults.byName("rest-client:a").reset();
            assertEquals(1, faults.activeFaultCount());
            assertEquals("none", faults.byName("rest-client:b").currentFault());

            // a family covers the targets named under it
            faults.byName("datasource").reset();
            assertEquals(0, faults.activeFaultCount());
        } finally {
            FaultsRuntime.close();
        }
    }

    @Test
    void listenerSeesAFaultStartAndEndWithTheGuardVerdict() {
        ProxyRegistry registry = FaultsRuntime.registry();
        try {
            // no connection goes through this proxy, so its upstream is never dialled
            registry.targetFor(TargetName.of("rest-client", "a"), Upstream.of("localhost", 1), "a.url");
            FaultsImpl faults = new FaultsImpl(true, List.of(), List.of(), List.of(), List.of());
            List<String> seen = new ArrayList<>();
            faults.addFaultListener(new FaultListener() {
                @Override
                public void started(FaultRecord record) {
                    seen.add("started " + record.targets() + " " + record.description());
                }

                @Override
                public void ended(FaultRecord record, FaultEnd end) {
                    seen.add("ended " + end.verdict() + " connections=" + end.connectionsSeen());
                }
            });
            faults.byName("rest-client:a").hangs();
            faults.resetAll();
            assertEquals(2, seen.size(), seen.toString());
            assertTrue(seen.get(0).startsWith("started ["), seen.toString());
            assertEquals("ended NO_TRAFFIC connections=0", seen.get(1));
        } finally {
            FaultsRuntime.close();
        }
    }

    @Test
    void aResetRacingTheStartIsNotSeenBeforeTheStart() throws InterruptedException {
        ProxyRegistry registry = FaultsRuntime.registry();
        try {
            // no connection goes through this proxy, so its upstream is never dialled
            registry.targetFor(TargetName.of("rest-client", "a"), Upstream.of("localhost", 1), "a.url");
            FaultsImpl faults = new FaultsImpl(true, List.of(), List.of(), List.of(), List.of());
            List<String> seen = new CopyOnWriteArrayList<>();
            faults.addFaultListener(new FaultListener() {
                @Override
                public void started(FaultRecord record) {
                    // another thread resets every fault while the start is being told
                    Thread reset = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            faults.resetAll();
                        }
                    });
                    reset.start();
                    try {
                        reset.join();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    seen.add("started");
                }

                @Override
                public void ended(FaultRecord record, FaultEnd end) {
                    seen.add("ended");
                }
            });
            faults.byName("rest-client:a").hangs();
            faults.resetAll();
            assertEquals(List.of("started", "ended"), seen);
        } finally {
            FaultsRuntime.close();
        }
    }
}
