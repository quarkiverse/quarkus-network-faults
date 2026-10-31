package io.quarkiverse.networkfaults.runtime.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.logging.Logger;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget.FaultHandle;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget.Outcome;
import io.quarkiverse.networkfaults.spi.ConsequenceCleaner;
import io.quarkiverse.networkfaults.spi.Exhaustion;
import io.quarkiverse.networkfaults.spi.ResourceTarget;

/**
 * Follows every fault handed to a test through {@link NetworkFaultsImpl}. When one ends, whichever way, two things
 * happen: the consequences are cleaned up (pools flushed, circuit breakers reset), and the no-traffic guard is
 * evaluated.
 * <p>
 * <b>The guard.</b> A proxy-based fault test can pass while testing nothing: the fault sits on a proxy the code
 * under test never talks to (the client resolved its address through another configuration key, the wrong bean
 * was named, a pooled connection was reused), the request fails or succeeds for unrelated reasons, and the
 * assertions happen to hold. The guard makes that a failure: if a fault was observable and no connection went
 * through any of its targets while it was active, or, for a fault on some requests only, none of those requests,
 * a violation naming the targets and the configuration properties they came from is recorded. Refused and dropped
 * connects never reach the proxy, so {@code unavailable} and {@code connectTimeout} are not observable and never
 * trigger it. When connections did arrive but the proxy could not pass them on because the upstream was
 * unreachable, the violation says that instead: the application was reset, and the place to look is the
 * dependency, not the configuration.
 * <p>
 * The guard records rather than throws: a fault usually ends inside a try-with-resources, where an exception from
 * {@code close()} would mask the assertion the test just failed with, or from {@code resetAll()} after the test
 * method has returned. The JUnit callback takes the recorded violations after each test and fails the test with
 * them.
 * <p>
 * A fault applied to a composite target is one group, judged by all of its members together: traffic through any
 * member is enough.
 * <p>
 * An exhaustion is a fault with no proxy: its guard is whether anything waited for the resource while it was held,
 * and a resource whose waiters cannot be observed is not judged. It settles the same way, and it is recorded and
 * reported the same way.
 */
public final class FaultTracker {

    private static final Logger LOG = Logger.getLogger(FaultTracker.class);

    private final List<ConsequenceCleaner> cleaners;
    private final Set<Group> active = new LinkedHashSet<>();
    // target name -> the exhaustion holding it
    private final Map<String, ResourceFault> exhausted = new LinkedHashMap<>();
    private final List<String> violations = new ArrayList<>();
    private final List<FaultListener> listeners = new CopyOnWriteArrayList<>();

    public FaultTracker(List<ConsequenceCleaner> cleaners) {
        this.cleaners = cleaners;
    }

    /**
     * A fault as the test sees it: ending it ends every member and settles the group.
     */
    final class Group implements ActiveFault {
        private final List<FaultHandle> members;
        private final FaultRecord record;

        private Group(List<FaultHandle> members) {
            this.members = members;
            List<String> targets = new ArrayList<>(members.size());
            for (FaultHandle h : members) {
                targets.add(h.target().name());
            }
            this.record = new FaultRecord(Instant.now(), List.copyOf(targets), members.getFirst().describe());
        }

        FaultRecord record() {
            return record;
        }

        @Override
        public void end() {
            for (FaultHandle h : members) {
                h.end();
            }
            settle(this);
        }
    }

    /**
     * A resource held in full as the test sees it: ending it gives the resource back and settles the fault.
     */
    private final class ResourceFault implements ActiveFault {
        private final String target;
        private final Exhaustion exhaustion;
        private final FaultRecord record;

        private ResourceFault(String target, Exhaustion exhaustion) {
            this.target = target;
            this.exhaustion = exhaustion;
            this.record = new FaultRecord(Instant.now(), List.of(target), "exhausted");
        }

        @Override
        public void end() {
            boolean last;
            synchronized (FaultTracker.this) {
                if (!exhausted.remove(target, this)) {
                    return;
                }
                last = exhausted.isEmpty();
            }
            try {
                exhaustion.release();
            } finally {
                if (last) {
                    cleanUp();
                }
                boolean watched = exhaustion.watched();
                boolean contended = watched && exhaustion.contended();
                if (watched && !contended) {
                    synchronized (FaultTracker.this) {
                        violations.add(target + " was exhausted, but nothing waited for it while it was:"
                                + " the request under test does not use this target, so the fault was not tested");
                    }
                }
                FaultEnd.Verdict verdict = !watched ? FaultEnd.Verdict.NOT_JUDGED
                        : contended ? FaultEnd.Verdict.SAW_TRAFFIC : FaultEnd.Verdict.NO_TRAFFIC;
                notifyEnded(record, new FaultEnd(Instant.now(), verdict, 0, 0));
            }
        }
    }

    /**
     * Tells {@code listener} of every fault that starts or ends from now on.
     */
    void addListener(FaultListener listener) {
        listeners.add(listener);
    }

    Group track(List<FaultHandle> handles) {
        Group group = new Group(handles);
        // told before the group is active, so a concurrent end cannot reach a listener ahead of the start
        notifyStarted(group.record);
        synchronized (this) {
            active.add(group);
        }
        return group;
    }

    /**
     * Takes the whole of {@code resource} as a fault on {@code targetName}. Recorded only once it is held, so a take
     * that fails leaves nothing behind. A target with a network fault is refused: its traffic would never reach the
     * wire the fault sits on.
     */
    ActiveFault trackExhaustion(String targetName, ResourceTarget resource) {
        synchronized (this) {
            if (exhausted.containsKey(targetName)) {
                throw new IllegalStateException("Target " + targetName + " is already exhausted; end that fault before"
                        + " applying another");
            }
            if (activeOn(targetName)) {
                throw new IllegalStateException("Target " + targetName + " has an active network fault, which an"
                        + " exhausted target would never meet; end that fault before exhausting it");
            }
        }
        ResourceFault fault = new ResourceFault(targetName, resource.exhaust());
        // told before the fault is active, so a concurrent end cannot reach a listener ahead of the start
        notifyStarted(fault.record);
        synchronized (this) {
            exhausted.put(targetName, fault);
        }
        return fault;
    }

    /**
     * Tells the listeners a fault has started; one listener failing does not stop the others.
     */
    private void notifyStarted(FaultRecord record) {
        for (FaultListener listener : listeners) {
            try {
                listener.started(record);
            } catch (RuntimeException e) {
                warn(listener, e);
            }
        }
    }

    /**
     * Runs cleanup and the guard once per group, whichever way it ended. Cleanup waits while anything is exhausted: a
     * cleaner acts on every pool, so it could give back what an exhaustion holds, and the last exhaustion to end runs it.
     */
    private void settle(Group group) {
        boolean clean;
        synchronized (this) {
            if (!active.remove(group)) {
                return;
            }
            clean = exhausted.isEmpty();
        }
        if (clean) {
            cleanUp();
        }
        List<FaultHandle> members = group.members;
        boolean observable = true;
        boolean traffic = false;
        for (FaultHandle h : members) {
            observable &= h.fault().observable();
            traffic |= h.outcome().sawTraffic();
        }
        if (observable && !traffic) {
            StringJoiner names = new StringJoiner(", ", "[", "]");
            Set<String> properties = new LinkedHashSet<>();
            Set<String> unreachable = new LinkedHashSet<>();
            for (FaultHandle h : members) {
                names.add(h.target().name());
                properties.add(h.target().sourceProperty());
                Outcome outcome = h.outcome();
                if (outcome.unreachable() > 0) {
                    unreachable.add(h.target().upstreamAddress() + " (" + outcome.unreachableBecause() + ")");
                }
            }
            FaultHandle applied = members.getFirst();
            String fault = "Target " + names + ": the '" + applied.describe() + "' fault was active but ";
            String verdict;
            if (unreachable.isEmpty()) {
                verdict = fault + "no " + applied.actsOn() + " went through the proxy while it was, so it cannot have "
                        + "affected the test. Is the application really using " + String.join(", ", properties) + "?";
            } else {
                // the application did connect; it is the dependency that was not there
                verdict = fault + "the proxy could not connect to the upstream " + String.join(", ", unreachable)
                        + ", so the application's connections were reset instead. A fault acts on a connection only "
                        + "once the upstream has accepted it. Is the dependency behind "
                        + String.join(", ", properties) + " running?";
            }
            synchronized (this) {
                violations.add(verdict);
            }
        }
        FaultEnd.Verdict verdict = !observable ? FaultEnd.Verdict.NOT_JUDGED
                : traffic ? FaultEnd.Verdict.SAW_TRAFFIC : FaultEnd.Verdict.NO_TRAFFIC;
        notifyEnded(group, verdict);
    }

    /**
     * Tells the listeners a fault has ended, with what its handles saw while it was active; one listener failing does
     * not stop the others.
     */
    private void notifyEnded(Group group, FaultEnd.Verdict verdict) {
        long connections = 0;
        long requests = 0;
        for (FaultHandle h : group.members) {
            Outcome outcome = h.outcome();
            connections += outcome.connectionsSeen();
            requests += outcome.httpRequestsSeen();
        }
        notifyEnded(group.record, new FaultEnd(Instant.now(), verdict, connections, requests));
    }

    private void notifyEnded(FaultRecord record, FaultEnd end) {
        for (FaultListener listener : listeners) {
            try {
                listener.ended(record, end);
            } catch (RuntimeException e) {
                warn(listener, e);
            }
        }
    }

    private static void warn(FaultListener listener, RuntimeException e) {
        LOG.warnf(e, "network faults: fault listener %s failed", listener.getClass().getName());
    }

    /**
     * Runs every cleaner; one failing does not stop the others.
     */
    private void cleanUp() {
        for (ConsequenceCleaner cleaner : cleaners) {
            try {
                cleaner.cleanUp();
            } catch (RuntimeException e) {
                LOG.warnf(e, "network faults: cleaning up after a fault failed in %s", cleaner.getClass().getName());
            }
        }
    }

    /**
     * Ends every fault still active, settling each one; see {@link #endEach}.
     */
    void endAll() {
        List<ActiveFault> faults;
        synchronized (this) {
            faults = new ArrayList<>(active);
            faults.addAll(exhausted.values());
        }
        endEach(faults);
    }

    /**
     * Ends every one of {@code faults}, whatever the others do, then throws the first failure with the rest suppressed.
     */
    static void endEach(List<ActiveFault> faults) {
        Throwable failure = null;
        for (ActiveFault fault : faults) {
            try {
                fault.end();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    /**
     * Ends every fault still active on any of {@code targets}, settling each one. A fault on a composite target ends
     * as a whole as soon as one of its members is among them.
     */
    void endOn(List<ProxyTarget> targets) {
        List<Group> groups = new ArrayList<>();
        synchronized (this) {
            for (Group g : active) {
                for (FaultHandle h : g.members) {
                    if (targets.contains(h.target())) {
                        groups.add(g);
                        break;
                    }
                }
            }
        }
        for (Group g : groups) {
            g.end();
        }
    }

    /**
     * How many faults are active right now.
     */
    synchronized int activeCount() {
        return active.size() + exhausted.size();
    }

    /**
     * Whether {@code targetName} is held by an exhaustion right now.
     */
    synchronized boolean exhausted(String targetName) {
        return exhausted.containsKey(targetName);
    }

    /**
     * Whether a network fault is active on {@code targetName} right now, alone or as a member of a composite target.
     */
    synchronized boolean activeOn(String targetName) {
        for (Group g : active) {
            for (FaultHandle h : g.members) {
                if (h.target().name().equals(targetName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The violations recorded since the last call; the list is cleared so the next test starts clean.
     */
    public synchronized List<String> takeViolations() {
        List<String> out = new ArrayList<>(violations);
        violations.clear();
        return out;
    }
}
