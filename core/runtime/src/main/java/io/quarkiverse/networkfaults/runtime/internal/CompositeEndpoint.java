package io.quarkiverse.networkfaults.runtime.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.quarkiverse.networkfaults.ActiveFault;
import io.quarkiverse.networkfaults.Advanced;
import io.quarkiverse.networkfaults.Endpoint;
import io.quarkiverse.networkfaults.Http;
import io.quarkiverse.networkfaults.HttpRequestRecord;
import io.quarkiverse.networkfaults.HttpResponse;
import io.quarkiverse.networkfaults.Moment;
import io.quarkiverse.networkfaults.Traffic;
import io.quarkiverse.networkfaults.runtime.internal.proxy.Fault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyTarget.FaultHandle;
import io.quarkiverse.networkfaults.runtime.internal.proxy.RequestCounter;
import io.quarkiverse.networkfaults.runtime.internal.proxy.RequestScope;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.BandwidthFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.HangFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.HangMidRequestFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.HttpResponseFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.LatencyFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.MidRequestFault;
import io.quarkiverse.networkfaults.runtime.internal.proxy.fault.SlicerFault;

/**
 * An endpoint as handed to tests: the requests a scope covers on one or more proxies, or everything that goes through
 * them for a {@link CompositeTarget}. This is where each operation of the API becomes a fault: one fault object,
 * acting on the scope's requests only when there is one, applied to every member and tracked as a single fault.
 */
class CompositeEndpoint implements Endpoint {

    private static final Comparator<HttpRequestRecord> REQUESTS_BY_TIME = new Comparator<>() {
        @Override
        public int compare(HttpRequestRecord a, HttpRequestRecord b) {
            return a.time().compareTo(b.time());
        }
    };

    final List<ProxyTarget> members;
    final FaultTracker tracker;
    private final RequestScope scope;
    // whether a fault on the whole target first ends the faults the members have, see ObservableTarget#replacing
    final boolean replacing;

    CompositeEndpoint(List<ProxyTarget> members, FaultTracker tracker, RequestScope scope, boolean replacing) {
        this.members = List.copyOf(members);
        this.tracker = tracker;
        this.scope = scope;
        this.replacing = replacing;
    }

    /**
     * An exhausted member takes no fault, and no reset of the connections its exhaustion holds, which would end that
     * exhaustion unnoticed.
     */
    void requireNotExhausted() {
        for (ProxyTarget t : members) {
            if (tracker.exhausted(t.name())) {
                throw new IllegalStateException("Target " + t.name() + " is exhausted, so nothing reaches its "
                        + "connections and a fault there would meet nothing; end that fault before applying another");
            }
        }
    }

    /**
     * Applies one fault to every member, tracked as a single fault. A fault on some members only would be half a
     * fault, so every member is checked before any is touched.
     */
    ActiveFault apply(Fault fault) {
        if (!scope.isAll() || fault.readsHttp()) {
            requireHttp(fault);
        }
        requireNotExhausted();
        if (replacing && scope.isAll()) {
            tracker.endOn(members);
        }
        for (ProxyTarget t : members) {
            t.requireCanApply(scope);
        }
        // the count of requests taken is one across the members, as the first n requests of a target are
        RequestCounter counter = scope.newCounter();
        List<FaultHandle> handles = new ArrayList<>(members.size());
        for (ProxyTarget t : members) {
            handles.add(t.apply(fault, scope, counter));
        }
        return tracker.track(handles);
    }

    /**
     * A fault that reads HTTP, or acts on some requests only, would do nothing, or hold the connection, on a member
     * that is not plaintext HTTP.
     */
    private void requireHttp(Fault fault) {
        List<String> others = opaqueMembers();
        if (!others.isEmpty()) {
            throw new UnsupportedOperationException("The '" + scope.describe(fault) + "' fault acts on the HTTP exchange, "
                    + "so it needs a target reached over plaintext HTTP/1.x, an http:// or ws:// address. Not one: "
                    + String.join(", ", others) + ". There, use a fault on the whole target that works on any "
                    + "protocol, such as diesMidRequest(afterBytes(n)), hangs() or unavailable().");
        }
    }

    /**
     * The members not reached over plaintext HTTP/1.x, as {@code name (property)}.
     */
    public List<String> opaqueMembers() {
        List<String> others = new ArrayList<>();
        for (ProxyTarget t : members) {
            if (!t.upstream().http()) {
                others.add(t.name() + " (" + t.sourceProperty() + ")");
            }
        }
        return others;
    }

    @Override
    public ActiveFault hangs() {
        return apply(new HangFault());
    }

    @Override
    public ActiveFault hangs(Moment moment) {
        return apply(new HangMidRequestFault(moment));
    }

    @Override
    public ActiveFault slow(Duration latency) {
        return apply(new LatencyFault(latency, Duration.ZERO, Advanced.Direction.BOTH));
    }

    @Override
    public ActiveFault diesMidRequest(Moment moment) {
        return apply(new MidRequestFault(MidRequestFault.Ending.RESET, moment));
    }

    @Override
    public ActiveFault closes(Moment moment) {
        return apply(new MidRequestFault(MidRequestFault.Ending.CLOSE, moment));
    }

    @Override
    public Http http() {
        return new Http() {
            @Override
            public HttpResponse respondsWith(int status) {
                HttpResponseFault fault = new HttpResponseFault(status);
                // every member shares the proxy's one event loop, which is where the response is refined
                return new HttpResponseConfig(members.getFirst().registry(), fault, apply(fault));
            }

            @Override
            public Endpoint forFirst(int n) {
                return new CompositeEndpoint(members, tracker, scope.first(n), replacing);
            }

            @Override
            public Endpoint every(int n) {
                return new CompositeEndpoint(members, tracker, scope.every(n), replacing);
            }

            @Override
            public Endpoint after(int n) {
                return new CompositeEndpoint(members, tracker, scope.after(n), replacing);
            }

            @Override
            public Endpoint requests(String method, String path) {
                return new CompositeEndpoint(members, tracker, scope.and(method, path), replacing);
            }
        };
    }

    @Override
    public Advanced advanced() {
        return new Advanced() {
            @Override
            public ActiveFault latency(Duration latency, Duration jitter, Direction direction) {
                return apply(new LatencyFault(latency, jitter, direction));
            }

            @Override
            public ActiveFault bandwidth(long bytesPerSecond, Direction direction) {
                return apply(new BandwidthFault(bytesPerSecond, direction));
            }

            @Override
            public ActiveFault slice(int chunkSize, Duration delay, Direction direction) {
                return apply(new SlicerFault(chunkSize, delay, direction));
            }
        };
    }

    @Override
    public Traffic traffic() {
        return new RequestTraffic();
    }

    /**
     * The requests the scope covers on every member.
     */
    class RequestTraffic implements Traffic {

        @Override
        public long httpRequestsSeen() {
            long sum = 0;
            for (ProxyTarget t : members) {
                sum += t.httpRequestsSeen(scope);
            }
            return sum;
        }

        @Override
        public List<HttpRequestRecord> httpRequests() {
            List<HttpRequestRecord> all = new ArrayList<>();
            for (ProxyTarget t : members) {
                all.addAll(t.httpRequests(scope));
            }
            all.sort(REQUESTS_BY_TIME);
            return all;
        }
    }

    @Override
    public String toString() {
        return "CompositeEndpoint[" + scope + " " + members + "]";
    }
}
