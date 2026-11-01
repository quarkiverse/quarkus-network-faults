package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkiverse.resiliencytesting.HttpRequestRecord;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetEvent;

/**
 * What a target has seen: counters, the request log and the event log. Written and read on the event loop only.
 */
final class TargetStats {

    private final BoundedLog<TargetEvent> events;
    private final BoundedLog<RequestEntry> httpRequests;
    // the requests whose headers, body or response are still to come, by the exchange the conversation follows
    private final Map<HttpExchange, RequestEntry> inFlight = new IdentityHashMap<>();
    long connectionsSeen;
    long connectionsReset;
    long upstreamConnectFailures;
    /**
     * Why the upstream could not be reached the last time, or null if it always could.
     */
    String lastUpstreamConnectFailure;
    long bytesUpstream;
    long bytesDownstream;
    // how many requests of each method and path, the query string left out, so that the requests any scope covers
    // can be counted from the start; one entry per distinct request line an application sends is little
    private final Map<RequestLine, Long> requestsByPath = new HashMap<>();

    private record RequestLine(String method, String path) {
    }

    /**
     * A request as it is recorded: the request line at once, the rest as it arrives.
     */
    private static final class RequestEntry {
        private final Instant time;
        private final long connectionId;
        private final HttpExchange request;
        private Map<String, List<String>> headers = Map.of();
        private String bodyFingerprint;
        private Duration responseTime;
        private Integer responseStatus;

        RequestEntry(Instant time, long connectionId, HttpExchange request) {
            this.time = time;
            this.connectionId = connectionId;
            this.request = request;
        }

        HttpRequestRecord record() {
            return new HttpRequestRecord(time, connectionId, request.method(), request.target(), headers, bodyFingerprint,
                    responseTime, responseStatus);
        }
    }

    TargetStats(int capacity) {
        this.events = new BoundedLog<>(capacity);
        this.httpRequests = new BoundedLog<>(capacity);
    }

    void log(TargetEvent.Type type, long connectionId, String detail) {
        events.add(new TargetEvent(Instant.now(), type, connectionId, detail));
    }

    void requestSeen(long connectionId, HttpExchange request) {
        RequestLine line = new RequestLine(request.method(), request.path());
        requestsByPath.compute(line, (k, before) -> before == null ? 1 : before + 1);
        Instant now = Instant.now();
        RequestEntry entry = new RequestEntry(now, connectionId, request);
        httpRequests.add(entry);
        inFlight.put(request, entry);
        events.add(new TargetEvent(now, TargetEvent.Type.REQUEST, connectionId, request.method() + " " + request.target()));
    }

    void requestHeaders(HttpExchange request, Map<String, List<String>> headers) {
        RequestEntry entry = inFlight.get(request);
        if (entry != null) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                copy.put(h.getKey(), List.copyOf(h.getValue()));
            }
            entry.headers = Collections.unmodifiableMap(copy);
        }
    }

    void requestBody(HttpExchange request, String fingerprint) {
        RequestEntry entry = inFlight.get(request);
        if (entry != null) {
            entry.bodyFingerprint = fingerprint;
        }
    }

    /**
     * The response to {@code request} has started: its response time and status are known and nothing more will be recorded.
     */
    void responseSeen(HttpExchange request, int status) {
        RequestEntry entry = inFlight.remove(request);
        if (entry != null) {
            entry.responseTime = Duration.between(entry.time, Instant.now());
            entry.responseStatus = status;
        }
    }

    /**
     * The connection is gone, so the requests still in flight on it will see no response.
     */
    void connectionGone(long connectionId) {
        inFlight.values().removeIf(requestEntry -> requestEntry.connectionId == connectionId);
    }

    void bytes(Direction direction, int size) {
        if (direction == Direction.UPSTREAM) {
            bytesUpstream += size;
        } else {
            bytesDownstream += size;
        }
    }

    List<TargetEvent> events() {
        return events.snapshot();
    }

    /**
     * The requests seen since the start that {@code scope} covers.
     */
    long httpRequestsSeen(RequestScope scope) {
        // the count of every request is the sum over the paths, so no counter of its own is kept
        long seen = 0;
        for (Map.Entry<RequestLine, Long> e : requestsByPath.entrySet()) {
            if (scope.covers(e.getKey().method(), e.getKey().path())) {
                seen += e.getValue();
            }
        }
        return seen;
    }

    /**
     * The most recent requests that {@code scope} covers.
     */
    List<HttpRequestRecord> httpRequests(RequestScope scope) {
        List<HttpRequestRecord> covered = new ArrayList<>();
        for (RequestEntry e : httpRequests.snapshot()) {
            if (scope.covers(e.request)) {
                covered.add(e.record());
            }
        }
        return covered;
    }
}
