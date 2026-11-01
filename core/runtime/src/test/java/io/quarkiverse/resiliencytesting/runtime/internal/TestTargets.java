package io.quarkiverse.resiliencytesting.runtime.internal;

import java.util.List;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;

/**
 * Gives tests of the proxy the same target a test of an application gets.
 */
public final class TestTargets {

    private TestTargets() {
    }

    public static ObservableTarget of(ProxyTarget... proxies) {
        return new CompositeTarget("test", List.of(proxies), new FaultTracker(List.of()));
    }

    /**
     * A target whose guard verdicts the test reads back through {@code takeViolations}.
     */
    public static ObservableTarget guarded(FaultTracker tracker, ProxyTarget... proxies) {
        return new CompositeTarget("test", List.of(proxies), tracker);
    }
}
