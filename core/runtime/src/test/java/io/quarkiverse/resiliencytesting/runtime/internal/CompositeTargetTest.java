package io.quarkiverse.resiliencytesting.runtime.internal;

import static io.quarkiverse.resiliencytesting.Moment.afterBytes;
import static io.quarkiverse.resiliencytesting.Moment.onNextWrite;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterChunks;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterHeaders;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyRegistry;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.TestUpstream;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;

/**
 * A target made of several proxies: one fault is applied to all of them.
 */
class CompositeTargetTest {

    private static final String REQUEST = "GET / HTTP/1.1\r\nHost: x\r\n\r\n";

    private TestUpstream echo;
    private ProxyRegistry registry;

    @BeforeEach
    void setUp() {
        echo = TestUpstream.echo();
        registry = new ProxyRegistry(5);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        echo.close();
    }

    private ProxyTarget proxy(String name) {
        return registry.targetFor(TargetName.of("api", name), Upstream.http("127.0.0.1", echo.port()),
                "test." + name + ".url");
    }

    /**
     * A proxy in front of something that is not HTTP, as a datasource is.
     */
    private ProxyTarget plainProxy(String name) {
        return registry.targetFor(TargetName.of("api", name), Upstream.of("127.0.0.1", echo.port()),
                "test." + name + ".url");
    }

    /**
     * Sends the request through the proxy and returns as much of the answer as the request is long.
     */
    private static String answer(ProxyTarget proxy) throws IOException {
        try (Socket s = new Socket("127.0.0.1", proxy.port())) {
            s.setSoTimeout(2_000);
            s.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readNBytes(REQUEST.length()), StandardCharsets.US_ASCII);
        }
    }

    @Test
    void forFirstCountsRequestsAcrossTheMembers() throws Exception {
        ProxyTarget first = proxy("a");
        ProxyTarget second = proxy("b");
        try (ActiveFault f = TestTargets.of(first, second).http().forFirst(1).http().respondsWith(503)) {
            // the one synthetic response goes to the member that is asked first
            assertTrue(answer(first).startsWith("HTTP/1.1 503"));
            // so the other member's request is relayed, and the echo upstream sends it back
            assertEquals(REQUEST, answer(second));
        }
    }

    @Test
    void aFaultOnAnUnreachableUpstreamSaysTheUpstreamIsUnreachable() throws Exception {
        int deadPort;
        try (ServerSocket ss = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            deadPort = ss.getLocalPort();
        }
        ProxyTarget dead = registry.targetFor(TargetName.of("api", "dead"), Upstream.http("127.0.0.1", deadPort),
                "test.dead.url");
        FaultTracker tracker = new FaultTracker(List.of());
        CompositeTarget target = new CompositeTarget("api:dead", List.of(dead), tracker);
        try (ActiveFault f = target.http().respondsWith(503)) {
            // the proxy cannot pass the connection on, so the application gets a reset instead of the 503
            String answer;
            try {
                answer = answer(dead);
            } catch (IOException reset) {
                answer = "";
            }
            assertFalse(answer.startsWith("HTTP/1.1 503"), answer);
        }
        List<String> violations = tracker.takeViolations();
        assertEquals(1, violations.size(), violations.toString());
        String verdict = violations.getFirst();
        assertTrue(verdict.contains("could not connect to the upstream 127.0.0.1:" + deadPort), verdict);
        assertTrue(verdict.contains("test.dead.url"), verdict);
        // not the verdict for a fault nothing went through, which sends the reader looking at the wrong thing
        assertFalse(verdict.contains("no connection went through"), verdict);
    }

    @Test
    void anHttpFaultOnATargetThatIsNotHttpIsRefused() {
        ObservableTarget database = TestTargets.of(plainProxy("db"));
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> database.http().respondsWith(503));
        // the message says which target, and which property made it what it is
        assertTrue(refused.getMessage().contains("api:db"), refused.getMessage());
        assertTrue(refused.getMessage().contains("test.db.url"), refused.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> database.diesMidRequest(afterHeaders()));
        assertThrows(UnsupportedOperationException.class, () -> database.diesMidRequest(afterChunks(1)));
        assertThrows(UnsupportedOperationException.class, () -> database.hangs(afterSseEvents(1)));
        assertEquals("none", database.currentFault());

        // the moments that read nothing work on any protocol
        try (ActiveFault f = database.diesMidRequest(afterBytes(10))) {
            assertEquals("diesMidRequest after 10 response byte(s)", database.currentFault());
        }
        try (ActiveFault f = database.hangs(onNextWrite())) {
            assertEquals("hangs on next write", database.currentFault());
        }
    }

    @Test
    void anHttpFaultIsRefusedForTheWholeTargetWhenOneMemberIsNotHttp() {
        ObservableTarget both = TestTargets.of(proxy("a"), plainProxy("db"));
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> both.http().respondsWith(503));
        assertTrue(refused.getMessage().contains("api:db"), refused.getMessage());
        assertFalse(refused.getMessage().contains("api:a"), refused.getMessage());
        // nothing was applied to the member that could have taken it
        assertEquals("none", both.currentFault());
    }

    @Test
    void aFaultOnSomeRequestsThatNoneOfThemWentThroughFailsTheGuard() throws Exception {
        ProxyTarget api = proxy("a");
        FaultTracker tracker = new FaultTracker(List.of());
        CompositeTarget target = new CompositeTarget("api:a", List.of(api), tracker);
        try (ActiveFault f = target.http().requests("POST", "/reserve").diesMidRequest()) {
            // a connection, and a request, but not one the fault covers
            assertEquals(REQUEST, answer(api));
        }
        List<String> violations = tracker.takeViolations();
        assertEquals(1, violations.size(), violations.toString());
        String verdict = violations.getFirst();
        assertTrue(verdict.contains("'diesMidRequest after 0 response byte(s) for POST /reserve'"), verdict);
        assertTrue(verdict.contains("no POST /reserve request went through"), verdict);
    }

    @Test
    void aFaultOnSomeRequestsOfATargetThatIsNotHttpIsRefused() {
        ObservableTarget database = TestTargets.of(plainProxy("db"));
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> database.http().requests("GET", "/").hangs());
        assertTrue(refused.getMessage().contains("api:db"), refused.getMessage());
        assertEquals("none", database.currentFault());
    }

    @Test
    void theGuardJudgesEachFaultOnSomeRequestsByItsOwnRequests() throws Exception {
        ProxyTarget api = proxy("a");
        FaultTracker tracker = new FaultTracker(List.of());
        CompositeTarget target = new CompositeTarget("api:a", List.of(api), tracker);
        try (ActiveFault seen = target.http().requests("GET", "/").slow(Duration.ofMillis(1));
                ActiveFault unseen = target.http().requests("POST", "/reserve").diesMidRequest()) {
            assertEquals(REQUEST, answer(api));
        }
        List<String> violations = tracker.takeViolations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains("no POST /reserve request went through"), violations.getFirst());
    }
}
