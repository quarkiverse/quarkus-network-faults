package io.quarkiverse.resiliencytesting.dev;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.HttpRequestRecord;
import io.quarkiverse.resiliencytesting.runtime.internal.ConnectionSnapshot;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkiverse.resiliencytesting.runtime.internal.ObservableTarget;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetEvent;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetSnapshot;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.runtime.annotations.DevMCPEnableByDefault;
import io.quarkus.runtime.annotations.JsonRpcDescription;

/**
 * Backs the Dev UI card: the same snapshots the test API exposes, plus fault toggles.
 * Return values are plain maps and lists so they serialize without any extra Jackson modules.
 * Every public method is called by name from the card's web component through JSON-RPC, and is a Dev MCP tool for
 * AI agents; the descriptions are written for an agent that has no other documentation. The tools that only read
 * are enabled by default, those that change the running application are opt-in, except {@code resetAll}, which only
 * ever ends faults.
 */
@ApplicationScoped
@SuppressWarnings("unused")
public class FaultsJsonRPCService {

    private static final String TARGET_DESCRIPTION = "A target name from getTargets, such as rest-client:inventory; "
            + "a prefix such as rest-client covers every target under it.";

    private final Faults faults;
    private final FaultHistory history;

    public FaultsJsonRPCService(Faults faults, FaultHistory history) {
        this.faults = faults;
        this.history = history;
    }

    private FaultsImpl impl() {
        return (FaultsImpl) faults;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Whether network fault injection is on in this dev-mode application, how many faults are "
            + "active, every proxied target, and a hint on where to go next. Call this first. Returns enabled "
            + "(boolean), hint (string), faultsActive (number of faults currently applied) and targets (the same "
            + "entries as getTargets).")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean enabled = impl().isEnabled();
        status.put("enabled", enabled);
        status.put("hint",
                enabled ? "Before writing a test that injects network faults, read the Dev MCP resource "
                        + "quarkus-resiliency-testing_testWritingGuide. To experiment against this running application "
                        + "first, follow quarkus-resiliency-testing_agentPlaybook, starting from the guarded methods in "
                        + "quarkus-resiliency-testing_inventory and the targets below. The tools apply, reset and "
                        + "dropConnections change the running application and are opt-in: if they are not available, "
                        + "ask the developer to enable them in the Dev UI, under Settings, Dev MCP tab, Tools."
                        : "Set " + Faults.ENABLED_PROPERTY + "=true to route outbound connections "
                                + "through fault-injecting proxies in dev mode.");
        status.put("faultsActive", impl().activeFaultCount());
        status.put("targets", getTargets());
        return status;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Every proxied outbound connection of the application (a target), sorted by name. Each entry "
            + "has name (pass it as target to the other tools), kind (the name's family: rest-client, datasource, "
            + "kafka, ...), upstream (host:port the proxy forwards to), proxyPort, sourceProperty (the configuration "
            + "property the address came from), plaintextHttp (true when the target is reached over plaintext "
            + "HTTP/1.x, so responds-with, a requests scope and getRequests apply to it), listening, fault (the "
            + "active faults, none when idle), previousFault, connection and byte counters, and the open "
            + "connections. A target appears once the application has read its configuration. Next: getEvents or "
            + "getRequests for one target, getFaults for what can be applied.")
    public List<Map<String, Object>> getTargets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TargetSnapshot s : impl().snapshot()) {
            out.add(toMap(s));
        }
        return out;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("The event log of a target, oldest first: proxy bound, connections opened and closed, faults "
            + "started and ended, connections reset, upstream connect failures. Each event has time, type, "
            + "connectionId and detail. The log keeps the most recent events only, so read it soon after the call "
            + "you want to explain. Use it to confirm a fault was exercised.")
    public List<Map<String, Object>> getEvents(
            @JsonRpcDescription(TARGET_DESCRIPTION + " all covers every target.") String target) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TargetEvent e : impl().byName(target).events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.time().toString());
            m.put("type", e.type().name());
            m.put("connectionId", e.connectionId());
            m.put("detail", e.detail());
            out.add(m);
        }
        return out;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("The most recent HTTP requests the application sent through a target, oldest first. Returns "
            + "requests and reason. Each request has time, connectionId, method, target (path and query string), "
            + "headers, bodyFingerprint (SHA-256 of the body, null without one), responseTimeMillis (null while "
            + "there is no response) and responseStatus (null while there is no response). Only targets reached "
            + "over plaintext HTTP/1.x show requests; for any other, reason says why the list is empty and which "
            + "property configures the target, and is empty otherwise. Use it to confirm the requests a fault was "
            + "meant for went through, and with which status they came back.")
    public Map<String, Object> getRequests(
            @JsonRpcDescription(TARGET_DESCRIPTION + " all covers every target.") String target) {
        ObservableTarget selected = impl().byName(target);
        List<String> opaque = new ArrayList<>();
        for (String member : selected.opaqueMembers()) {
            opaque.add(notHttpReason(member));
        }
        List<Map<String, Object>> requests = new ArrayList<>();
        for (HttpRequestRecord r : selected.traffic().httpRequests()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", r.time().toString());
            m.put("connectionId", r.connectionId());
            m.put("method", r.method());
            m.put("target", r.target());
            m.put("headers", r.headers());
            m.put("bodyFingerprint", r.bodyFingerprint());
            m.put("responseTimeMillis", r.responseTime() == null ? null : r.responseTime().toMillis());
            m.put("responseStatus", r.responseStatus());
            requests.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", requests);
        out.put("reason", String.join("; ", opaque));
        return out;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Every form the fault argument of apply accepts, with a sample argument where one is "
            + "needed. Returns a list of {spec, meaning, readsHttp, respondsWith}: spec is what to pass as fault, "
            + "meaning what the application sees, readsHttp whether the fault needs a target with plaintextHttp, "
            + "respondsWith whether it takes a body and a content type.")
    public List<FaultSpec.Example> getFaults() {
        return FaultSpec.examples();
    }

    /**
     * Applies the fault to the target as the card does, see {@link ObservableTarget#replacing()}: a fault on the whole
     * target first ends the target's faults, so the card switches faults in one click; one on some requests ends
     * nothing, so several request faults can be combined, and a conflict is reported as the API reports it.
     */
    @JsonRpcDescription("Applies a network fault to a target in the running application; it stays active until "
            + "reset or resetAll. Without requests, any fault already on the target is ended first. With requests, "
            + "nothing is ended and the fault acts on those requests only; one that conflicts with an active fault "
            + "is refused with the reason. Returns a confirmation. Next: call the application's endpoint yourself, "
            + "then confirm the fault was exercised with getEvents and getTargets (under hangs the request is held "
            + "before the proxy reads it, so its connection shows held: true and getRequests lists it only once the "
            + "fault ends), then reset; after the reset, getRequests and getHistory's verdict confirm it too.")
    public String apply(
            @JsonRpcDescription(TARGET_DESCRIPTION) String target,
            @JsonRpcDescription("The fault, one of: unavailable (connections refused); connect-timeout (connecting "
                    + "never completes); hangs (accepted, never answered); slow:<duration>, for example slow:10s or "
                    + "slow:500ms; dies-mid-request (reset while the request is in flight); closes (closed "
                    + "cleanly); dies-mid-request, closes or hangs followed by a moment: :<bytes> as in "
                    + "dies-mid-request:512, :next-write as in dies-mid-request:next-write, :headers as in "
                    + "dies-mid-request:headers, :chunks:<n> as in dies-mid-request:chunks:2, :sse:<n> as in "
                    + "hangs:sse:3; responds-with:<status>, for example responds-with:503, which answers instead of "
                    + "the upstream. The :headers, :chunks:<n> and :sse:<n> moments, like responds-with, need a "
                    + "plaintext HTTP target. getFaults lists them all.") String fault,
            @JsonRpcDescription("Optional, empty or omitted to fault every connection. \"METHOD /path\" to fault "
                    + "only those requests, for example \"POST /inventory/reserve\"; the target must be plaintext "
                    + "HTTP. Not accepted with unavailable or connect-timeout.") String requests,
            @JsonRpcDescription("Optional, empty or omitted for none; responds-with only: the response body.") String body,
            @JsonRpcDescription("Optional, empty or omitted for none; responds-with only: the response content type, "
                    + "for example application/json.") String contentType,
            @JsonRpcDescription("Optional, empty or omitted for none; responds-with only: extra response headers, "
                    + "one \"Name: value\" per line, for example \"Retry-After: 30\".") String headers) {
        FaultRequest request = new FaultRequest(fault, absentIfBlank(requests), absentIfBlank(body),
                absentIfBlank(contentType), absentIfBlank(headers));
        FaultSpec.Parsed parsed = FaultSpec.parse(request);
        ActiveFault applied = FaultSpec.apply(parsed, impl().byName(target).replacing());
        history.tag(impl().recordOf(applied), request);
        return "applied " + fault + " to " + target;
    }

    @JsonRpcDescription("Ends every fault on a target, whichever way it was applied, and returns a confirmation. "
            + "Call it when an experiment is done.")
    public String reset(
            @JsonRpcDescription(TARGET_DESCRIPTION) String target) {
        impl().byName(target).reset();
        return "reset " + target;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Ends every active fault on every target and returns a confirmation. Safe to call at any "
            + "time; call it when you are done experimenting.")
    public String resetAll() {
        faults.resetAll();
        return "all faults ended";
    }

    @JsonRpcDescription("Closes every open connection through a target, as if the network dropped them, so the "
            + "application has to reconnect; no fault is left active. Returns a confirmation.")
    public String dropConnections(
            @JsonRpcDescription(TARGET_DESCRIPTION) String target) {
        impl().byName(target).dropConnections();
        return "dropped connections of " + target;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Every fault applied in this application since it started, newest first, whether from the "
            + "Dev UI card, a Dev MCP tool or application code; the most recent 500 only. Each entry has id, started, "
            + "ended (null while the fault is active), targets, description, spec and requests (as passed to apply, "
            + "null for a fault applied from code), source (card for apply, api for application code), verdict "
            + "(null while active; saw-traffic when a connection, or a request the fault was scoped to, went through "
            + "while it was active; no-traffic when nothing did, so the fault cannot have affected the application; "
            + "not-judged for unavailable and connect-timeout, which refuse or drop connections before the proxy "
            + "sees them, so their counts mean nothing), connectionsSeen and httpRequestsSeen (while it was active). "
            + "Use it to check that an experiment exercised the fault, then getReport to summarise it.")
    public List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FaultHistory.Entry e : history.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("started", e.started().toString());
            m.put("ended", e.ended() == null ? null : e.ended().toString());
            m.put("targets", e.targets());
            m.put("description", e.description());
            m.put("spec", e.spec());
            m.put("requests", e.requests());
            m.put("source", e.spec() == null ? "api" : "card");
            m.put("verdict", verdict(e));
            m.put("connectionsSeen", e.connectionsSeen());
            m.put("httpRequestsSeen", e.httpRequestsSeen());
            out.add(m);
        }
        return out;
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("A Markdown report of the fault experiments run against this application: the targets with "
            + "their configuration property, upstream and active fault, every fault in getHistory with whether "
            + "traffic went through it, and the connection, request, reset and upstream failure counters per target. "
            + "Returns markdown and generatedAt. Call it when an experiment is done, to show the developer or to "
            + "paste into an issue or pull request.")
    public Map<String, Object> getReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markdown", MarkdownReport.render(impl().snapshot(), history.entries()));
        out.put("generatedAt", Instant.now().toString());
        return out;
    }

    /**
     * An optional argument as {@link FaultRequest} takes it. The parameters are plain strings rather than
     * {@code Optional}: Quarkus looks the method up by the {@code Optional}'s type argument and fails to start.
     * A Dev MCP client sees them as required and may send an empty string, which means absent too.
     */
    private static String absentIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Map<String, Object> toMap(TargetSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("kind", TargetName.parse(s.name()).family());
        m.put("upstream", s.upstream());
        m.put("proxyPort", s.proxyPort());
        m.put("sourceProperty", s.sourceProperty());
        m.put("plaintextHttp", s.plaintextHttp());
        m.put("notHttpReason", s.plaintextHttp() ? null : notHttpReason(s.name() + " (" + s.sourceProperty() + ")"));
        m.put("lastFault", lastFault(s.name()));
        m.put("listening", s.listening());
        m.put("fault", s.fault());
        m.put("previousFault", s.previousFault());
        m.put("connectionsSeen", s.connectionsSeen());
        m.put("httpRequestsSeen", s.httpRequestsSeen());
        m.put("connectionsReset", s.connectionsReset());
        m.put("upstreamConnectFailures", s.upstreamConnectFailures());
        m.put("bytesUpstream", s.bytesUpstream());
        m.put("bytesDownstream", s.bytesDownstream());
        List<Map<String, Object>> conns = new ArrayList<>();
        for (ConnectionSnapshot c : s.connections()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("client", c.clientAddress());
            cm.put("openedAt", c.openedAt().toString());
            cm.put("lastActivity", c.lastActivity().toString());
            cm.put("bytesUpstream", c.bytesUpstream());
            cm.put("bytesDownstream", c.bytesDownstream());
            cm.put("held", c.held());
            conns.add(cm);
        }
        m.put("connections", conns);
        return m;
    }

    /**
     * The last fault that ended on a target, with its verdict: what the card shows next to a target that has no
     * fault active, which is the dev-mode form of the test guard.
     */
    private Map<String, Object> lastFault(String target) {
        FaultHistory.Entry last = history.lastEnded(target);
        if (last == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fault", last.spec() == null ? last.description() : last.spec());
        m.put("verdict", verdict(last));
        m.put("connectionsSeen", last.connectionsSeen());
        m.put("httpRequestsSeen", last.httpRequestsSeen());
        return m;
    }

    private static String verdict(FaultHistory.Entry e) {
        return e.verdict() == null ? null : e.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String notHttpReason(String member) {
        return member + " is not reached over plaintext HTTP/1.x, so the proxy cannot see its requests, scope a fault to"
                + " some of them, or apply a fault that reads the HTTP exchange";
    }
}
