package io.quarkiverse.networkfaults.runtime.internal.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.jboss.logging.Logger;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.HttpRequestRecord;
import io.quarkiverse.networkfaults.runtime.internal.ConnectionSnapshot;
import io.quarkiverse.networkfaults.runtime.internal.TargetEvent;
import io.quarkiverse.networkfaults.runtime.internal.TargetSnapshot;
import io.quarkiverse.networkfaults.spi.AddressUtil;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.Upstream;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;

/**
 * A proxy in front of one upstream address. Every piece of its state, the active faults, the listener, the
 * connections and the counters, is written on the proxy's event loop and nowhere else: a fault applied from a
 * test thread is a message to the loop that the caller waits for, and a reading from a test thread is a value the
 * loop computed. Nothing here needs a lock. The one rule is that the loop never waits for itself, which
 * {@link ProxyRegistry#call} and {@link ProxyRegistry#callAsync} enforce.
 */
public final class ProxyTarget {

    private static final Logger LOG = Logger.getLogger(ProxyTarget.class);

    private final ProxyRegistry registry;
    private final TargetName name;
    private final Upstream upstream;
    private final String sourceProperty;
    private final Listener listener;
    private final TargetStats stats;
    /**
     * Insertion order is id order: ids only grow.
     */
    private final Map<Long, ProxyConnection> connections = new LinkedHashMap<>();
    private long nextConnectionId;
    // the active faults, in the order they were applied: a buffer goes to the first whose scope covers its request.
    // A fault on the whole target has it to itself, so it is the only one then.
    private final List<FaultHandle> faults = new ArrayList<>();
    private String previousFault = "none";

    ProxyTarget(ProxyRegistry registry, TargetName name, Upstream upstream, String sourceProperty,
            int eventCapacity, int port) {
        this.registry = registry;
        this.name = name;
        this.upstream = upstream;
        this.sourceProperty = sourceProperty;
        this.listener = new Listener(this, port);
        this.stats = new TargetStats(eventCapacity);
    }

    // ---- identity, readable from any thread ------------------------------------------------------------------

    public String name() {
        return name.toString();
    }

    public TargetName targetName() {
        return name;
    }

    public Upstream upstream() {
        return upstream;
    }

    /**
     * The upstream as {@code host:port}, for logs.
     */
    public String upstreamAddress() {
        return upstream.host() + ":" + upstream.port();
    }

    public String sourceProperty() {
        return sourceProperty;
    }

    public ProxyRegistry registry() {
        return registry;
    }

    /**
     * Counters and logs; on the loop.
     */
    TargetStats stats() {
        return stats;
    }

    // ---- loop-side state, exposed to connections and faults -------------------------------------------------

    /**
     * Routes a buffer to a fault: the first active fault whose scope covers its exchange, null for what is not a
     * request, owns the request; a scope that counts requests decides at the request's first buffer whether to take
     * it or relay it, and its response follows that decision. A fault that takes a request has thereby seen one. On
     * the loop.
     */
    Fault routeTo(ProxyConnection c, Direction direction, HttpExchange exchange, boolean starts) {
        for (FaultHandle h : faults) {
            if (!h.scope.covers(exchange)) {
                continue;
            }
            if (h.counter == null) {
                h.sawRequest = true;
                return h.fault;
            }
            // the request a counting fault took on this connection, decided at the request's first buffer and
            // followed by the rest of it and by its response; null when the current request was relayed
            HttpExchange taken = (HttpExchange) c.faultState(h);
            if (direction == Direction.UPSTREAM && starts) {
                taken = h.counter.next() ? exchange : null;
                c.faultState(h, taken);
            }
            if (taken != exchange) {
                return Fault.NONE;
            }
            h.sawRequest = true;
            return h.fault;
        }
        return Fault.NONE;
    }

    /**
     * The fault that acts on connections as they open: only a fault on the whole target does. On the loop.
     */
    Fault connectionFault() {
        for (FaultHandle h : faults) {
            if (h.scope.isAll()) {
                return h.fault;
            }
        }
        return Fault.NONE;
    }

    public List<ProxyConnection> liveConnections() {
        return new ArrayList<>(connections.values());
    }

    /**
     * Reset every live connection now; on the loop.
     */
    public void resetConnections() {
        for (ProxyConnection c : liveConnections()) {
            c.reset();
        }
    }

    void accept(NetSocket socket) {
        new ProxyConnection(++nextConnectionId, this, socket).start();
    }

    void connectionOpened(ProxyConnection c) {
        connections.put(c.id(), c);
        stats.connectionsSeen++;
        stats.log(TargetEvent.Type.CONNECTION_OPENED, c.id(), "client " + c.clientAddress());
    }

    void connectionClosed(ProxyConnection c, String reason) {
        if (connections.remove(c.id()) != null) {
            stats.log(TargetEvent.Type.CONNECTION_CLOSED, c.id(), reason);
        }
        stats.connectionGone(c.id());
    }

    void connectionReset(ProxyConnection c) {
        // Only a connection that actually opened counts as reset; a connection abandoned because the upstream
        // connect failed is recorded under upstreamConnectFailures instead, never in the map.
        if (connections.remove(c.id()) != null) {
            stats.connectionsReset++;
            stats.log(TargetEvent.Type.CONNECTION_RESET, c.id(), "reset by " + activeFaults());
        }
        stats.connectionGone(c.id());
    }

    void upstreamConnectFailed(ProxyConnection c, Throwable cause) {
        stats.upstreamConnectFailures++;
        stats.lastUpstreamConnectFailure = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        stats.log(TargetEvent.Type.UPSTREAM_CONNECT_FAILED, c.id(), upstreamAddress() + ": " + cause);
        // The application connected to the proxy and gets a reset, which says nothing about the upstream: say it here.
        LOG.warnf("network faults: %s cannot connect to its upstream %s (from %s): %s. The connection the application "
                + "opened was reset%s.", name, upstreamAddress(), sourceProperty, stats.lastUpstreamConnectFailure,
                faults.isEmpty() ? "" : ", so the '" + activeFaults() + "' fault could not act on it");
    }

    // ---- lifecycle, called from other threads ------------------------------------------------------------------

    /**
     * Bind; waits until the port is known.
     */
    void bind() {
        registry.callAsync(listener::bind);
    }

    public int port() {
        return registry.call(listener::port);
    }

    void close() {
        registry.callAsync(() -> endAll(false).compose(v -> listener.close()).andThen(ar -> resetConnections()));
    }

    // ---- faults, called from other threads ----------------------------------------------------------------------

    /**
     * Apply a fault to the requests {@code scope} covers, {@link RequestScope#ALL} for the whole target, and return the
     * handle that ends it. A fault on the whole target has the target to itself; faults on some requests share it,
     * as long as no two cover the same requests. One fault may be applied to several targets; each gets its own
     * handle, and {@code counter}, the scope's count of the requests it takes or null, is shared by all of them so
     * that the first {@code n} requests are the first {@code n} across the targets.
     *
     * @throws IllegalStateException if a fault in the way is active, see {@link #requireCanApply}
     */
    public FaultHandle apply(Fault next, RequestScope scope, RequestCounter counter) {
        return registry.callAsync(() -> {
            IllegalStateException conflict = conflict(scope);
            if (conflict != null) {
                return Future.failedFuture(conflict);
            }
            FaultHandle handle = new FaultHandle(this, next, scope, counter, stats.connectionsSeen,
                    stats.httpRequestsSeen(scope), connections.size(), stats.upstreamConnectFailures);
            faults.add(handle);
            if (!scope.isAll()) {
                // a fault on some requests acts on their buffers only, never on a connection or the listener
                started(handle);
                return Future.succeededFuture(handle);
            }
            return listener.set(next.listenerState())
                    .compose(x -> {
                        next.onStart(this);
                        started(handle);
                        return Future.succeededFuture(handle);
                    })
                    .recover(failure -> {
                        faults.remove(handle);
                        return listener.set(ListenerState.LISTENING).transform(x -> Future.failedFuture(failure));
                    });
        });
    }

    private void started(FaultHandle handle) {
        stats.log(TargetEvent.Type.FAULT_STARTED, TargetEvent.NO_CONNECTION, handle.describe());
        LOG.debugf("network fault '%s' applied to %s", handle.describe(), name);
    }

    /**
     * Fails if a fault in the way of one on {@code scope} is active: a composite checks every member before applying a
     * fault to any.
     *
     * @throws IllegalStateException if a fault on the whole target is active, if a fault on the whole target is to
     *         be applied while faults on some requests are, or if a fault on the same requests is active
     */
    public void requireCanApply(RequestScope scope) {
        registry.run(() -> {
            IllegalStateException conflict = conflict(scope);
            if (conflict != null) {
                throw conflict;
            }
        });
    }

    private IllegalStateException conflict(RequestScope scope) {
        for (FaultHandle h : faults) {
            if (h.scope.isAll()) {
                return new IllegalStateException("Target [" + name + "] already has the '" + h.describe()
                        + "' fault active: end it before applying another. A fault on the whole target has it to itself.");
            }
            if (scope.isAll()) {
                return new IllegalStateException("Target [" + name + "] already has the '" + activeFaults()
                        + "' fault active on some of its requests: end it before applying a fault to the whole target.");
            }
            if (h.scope.equals(scope)) {
                return new IllegalStateException("Target [" + name + "] already has the '" + h.describe()
                        + "' fault active on the same requests: end it before applying another to them.");
            }
        }
        return null;
    }

    /**
     * The active faults in words, {@code none} when there is none.
     */
    private String activeFaults() {
        if (faults.isEmpty()) {
            return "none";
        }
        StringJoiner all = new StringJoiner(", ");
        for (FaultHandle h : faults) {
            all.add(h.describe());
        }
        return all.toString();
    }

    /**
     * Ends the fault of {@code handle} if it is still active; on the loop. The listener goes back to accepting
     * unless the target is closing, in which case rebinding just to unbind again would be wasted.
     */
    private Future<Void> end(FaultHandle handle, boolean restoreListener) {
        if (!faults.remove(handle)) {
            return Future.succeededFuture(); // ended already
        }
        previousFault = handle.describe();
        handle.fault.onEnd(this);
        for (ProxyConnection c : connections.values()) {
            c.faultState(handle.fault, null);
            c.faultState(handle, null);
        }
        boolean relisten = restoreListener && handle.scope.isAll();
        Future<Void> restored = relisten ? listener.set(ListenerState.LISTENING) : Future.succeededFuture();
        return restored.andThen(ar -> {
            stats.log(TargetEvent.Type.FAULT_ENDED, TargetEvent.NO_CONNECTION, handle.describe());
            boolean connectionsSeen = stats.connectionsSeen != handle.startSeen || handle.startLive > 0;
            handle.outcome = new Outcome(handle.scope.isAll() ? connectionsSeen : handle.sawRequest,
                    stats.upstreamConnectFailures - handle.startUnreachable, stats.lastUpstreamConnectFailure,
                    stats.connectionsSeen - handle.startSeen,
                    stats.httpRequestsSeen(handle.scope) - handle.startRequests);
        });
    }

    private Future<Void> endAll(boolean restoreListener) {
        List<Future<Void>> ended = new ArrayList<>();
        for (FaultHandle h : new ArrayList<>(faults)) {
            ended.add(end(h, restoreListener));
        }
        return Future.all(ended).mapEmpty();
    }

    /**
     * What a fault saw while it was active, for the no-traffic guard.
     *
     * @param sawTraffic whether what the fault acts on went through the proxy: a connection for a fault on the whole
     *        target, a request it covers for a fault on some requests
     * @param unreachable how many connections the application opened that the proxy could not pass on, because it
     *        could not connect to the upstream
     * @param unreachableBecause why the upstream could not be reached, when {@code unreachable} is not zero
     * @param connectionsSeen how many connections the target accepted while the fault was active
     * @param httpRequestsSeen how many requests the fault's scope covers went through while it was active
     */
    public record Outcome(boolean sawTraffic, long unreachable, String unreachableBecause, long connectionsSeen,
            long httpRequestsSeen) {
    }

    /**
     * Handle for an applied fault. Ending it is idempotent and only ends the fault it started.
     */
    public static final class FaultHandle implements ActiveFault {
        private final ProxyTarget target;
        private final Fault fault;
        private final RequestScope scope;
        // counts the requests the scope takes, shared with the handles of the other members; null when it takes every
        // request it covers
        private final RequestCounter counter;
        private final long startSeen;
        private final long startRequests;
        private final int startLive;
        private final long startUnreachable;
        // on the loop: whether a request the scope covers went through while the fault was active
        private boolean sawRequest;
        // on the loop: set once, when the fault ends
        private Outcome outcome;

        FaultHandle(ProxyTarget target, Fault fault, RequestScope scope, RequestCounter counter, long startSeen,
                long startRequests, int startLive, long startUnreachable) {
            this.target = target;
            this.fault = fault;
            this.scope = scope;
            this.counter = counter;
            this.startSeen = startSeen;
            this.startRequests = startRequests;
            this.startLive = startLive;
            this.startUnreachable = startUnreachable;
        }

        public ProxyTarget target() {
            return target;
        }

        public Fault fault() {
            return fault;
        }

        public String describe() {
            return scope.describe(fault);
        }

        /**
         * What the fault acts on, for the no-traffic guard: "no {@code actsOn} went through the proxy".
         */
        public String actsOn() {
            if (scope.isAll()) {
                return "connection";
            }
            return counter == null ? scope + " request" : "request the fault selected";
        }

        /**
         * What the fault saw while it was active, for the no-traffic guard; null until it has ended.
         */
        public Outcome outcome() {
            return target.registry.call(() -> outcome);
        }

        @Override
        public void end() {
            target.registry.callAsync(() -> target.end(this, true));
        }
    }

    /**
     * Reset every live connection now: pooled connections come back broken. One-shot, not a fault.
     */
    public void dropConnections() {
        registry.run(() -> {
            int count = connections.size();
            resetConnections();
            stats.log(TargetEvent.Type.CONNECTIONS_DROPPED, TargetEvent.NO_CONNECTION, count + " connection(s)");
        });
    }

    // ---- observation: values the loop computes ------------------------------------------------------------------

    /**
     * The active faults' descriptions, {@code none} when idle.
     */
    public String currentFault() {
        return registry.call(this::activeFaults);
    }

    public int connections() {
        return registry.call(connections::size);
    }

    /**
     * The live connections a fault is holding on either side.
     */
    public int heldConnections() {
        return registry.call(() -> {
            int held = 0;
            for (ProxyConnection c : connections.values()) {
                if (c.isHeld()) {
                    held++;
                }
            }
            return held;
        });
    }

    public long connectionsSeen() {
        return registry.call(() -> stats.connectionsSeen);
    }

    /**
     * The HTTP requests seen since the application started that {@code scope} covers.
     */
    public long httpRequestsSeen(RequestScope scope) {
        return registry.call(() -> stats.httpRequestsSeen(scope));
    }

    /**
     * The most recent HTTP requests that {@code scope} covers, oldest first.
     */
    public List<HttpRequestRecord> httpRequests(RequestScope scope) {
        return registry.call(() -> stats.httpRequests(scope));
    }

    /**
     * A consistent view: every field taken on the loop in one go.
     */
    public TargetSnapshot snapshot() {
        return registry.call(() -> {
            List<ConnectionSnapshot> conns = new ArrayList<>();
            for (ProxyConnection c : connections.values()) {
                conns.add(c.snapshot());
            }
            return new TargetSnapshot(name.toString(), upstream.host(), upstream.port(), listener.port(), sourceProperty,
                    upstream.http(), listener.isListening(), activeFaults(), previousFault, stats.connectionsSeen,
                    stats.httpRequestsSeen(RequestScope.ALL), stats.connectionsReset, stats.upstreamConnectFailures,
                    stats.bytesUpstream,
                    stats.bytesDownstream, List.copyOf(conns));
        });
    }

    /**
     * The event log, oldest first.
     */
    public List<TargetEvent> events() {
        return registry.call(stats::events);
    }

    @Override
    public String toString() {
        return "ProxyTarget[" + name + " " + AddressUtil.PROXY_HOST + ":" + port() + " -> " + upstreamAddress() + "]";
    }
}
