package io.quarkiverse.networkfaults.runtime.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.TargetTraffic;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget;
import io.quarkiverse.networkfaults.runtime.internal.proxy.RequestScope;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.ConnectTimeoutFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.UnavailableFault;

/**
 * A target as handed to tests: one or more proxies (a datasource with JDBC and reactive connections, a Kafka
 * cluster, "all"), whose faults act on everything that goes through them.
 */
final class CompositeTarget extends CompositeEndpoint implements ObservableTarget {

    private static final Comparator<TargetEvent> EVENTS_BY_TIME = new Comparator<>() {
        @Override
        public int compare(TargetEvent a, TargetEvent b) {
            return a.time().compareTo(b.time());
        }
    };

    /**
     * How often {@link TargetTraffic#awaitConnections} looks again: short enough that a test waits no longer than it
     * must, long enough not to keep the event loop busy answering.
     */
    private static final long AWAIT_POLL_MILLIS = 10;

    private final String name;

    CompositeTarget(String name, List<ProxyTarget> members, FaultTracker tracker) {
        this(name, members, tracker, false);
    }

    private CompositeTarget(String name, List<ProxyTarget> members, FaultTracker tracker, boolean replacing) {
        super(members, tracker, RequestScope.ALL, replacing);
        this.name = name;
    }

    @Override
    public ObservableTarget replacing() {
        return new CompositeTarget(name, members, tracker, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ActiveFault unavailable() {
        return apply(new UnavailableFault());
    }

    @Override
    public ActiveFault connectTimeout() {
        return apply(new ConnectTimeoutFault());
    }

    @Override
    public void dropConnections() {
        requireNotExhausted();
        for (ProxyTarget t : members) {
            t.dropConnections();
        }
    }

    @Override
    public String currentFault() {
        Set<String> faults = new LinkedHashSet<>();
        for (ProxyTarget t : members) {
            faults.add(t.currentFault());
        }
        return String.join(", ", faults);
    }

    @Override
    public TargetTraffic traffic() {
        return new ConnectionTraffic();
    }

    /**
     * Every request on the members, and the connections that carried them.
     */
    final class ConnectionTraffic extends RequestTraffic implements TargetTraffic {

        @Override
        public int connections() {
            int sum = 0;
            for (ProxyTarget t : members) {
                sum += t.connections();
            }
            return sum;
        }

        @Override
        public long connectionsSeen() {
            long sum = 0;
            for (ProxyTarget t : members) {
                sum += t.connectionsSeen();
            }
            return sum;
        }

        @Override
        public void awaitConnections(int atLeast, Duration timeout) {
            await(new Count() {
                @Override
                public int now() {
                    return connections();
                }
            }, atLeast, timeout, "open");
        }

        @Override
        public void awaitHeldConnections(int atLeast, Duration timeout) {
            await(new Count() {
                @Override
                public int now() {
                    int held = 0;
                    for (ProxyTarget t : members) {
                        held += t.heldConnections();
                    }
                    return held;
                }
            }, atLeast, timeout, "held by a fault");
        }

        private void await(Count count, int atLeast, Duration timeout, String state) {
            long deadline = System.nanoTime() + timeout.toNanos();
            int seen = count.now();
            while (seen < atLeast) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("Expected at least " + atLeast + " connection(s) to " + name + " " + state
                            + " within " + timeout + ", but " + seen + " were.");
                }
                try {
                    Thread.sleep(AWAIT_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for connections to " + name, e);
                }
                seen = count.now();
            }
        }
    }

    /**
     * A number of connections in some state, read again on each poll of {@code await}.
     */
    private interface Count {
        int now();
    }

    @Override
    public List<TargetEvent> events() {
        List<TargetEvent> all = new ArrayList<>();
        for (ProxyTarget t : members) {
            all.addAll(t.events());
        }
        all.sort(EVENTS_BY_TIME);
        return all;
    }

    @Override
    public void reset() {
        tracker.endOn(members);
    }

    @Override
    public String toString() {
        return "CompositeTarget[" + name + " " + members + "]";
    }
}
