package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static io.quarkiverse.resiliencytesting.Moment.afterBytes;
import static io.quarkiverse.resiliencytesting.Moment.onNextWrite;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterChunks;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterHeaders;
import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.ascii;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.expectReset;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.millis;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.readExactly;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.readHead;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.HttpRequestRecord;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetEvent;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetSnapshot;
import io.quarkiverse.resiliencytesting.runtime.internal.TestTargets;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class ProxyCoreTest {

    private TestUpstream echo;
    private ProxyRegistry registry;
    private ProxyTarget target;

    @BeforeEach
    void setUp() {
        echo = TestUpstream.echo();
        registry = new ProxyRegistry(5);
        target = registry.targetFor(TargetName.of("echo"), Upstream.http("127.0.0.1", echo.port()), "test.echo.url");
    }

    @AfterEach
    void tearDown() {
        registry.close();
        echo.close();
    }

    /**
     * The proxy behind the API a test gets, which is where each operation becomes a fault.
     */
    private static Target api(ProxyTarget proxy) {
        return TestTargets.of(proxy);
    }

    private Socket connect() throws IOException {
        return Sockets.connect(target);
    }

    private Socket connect(int connectTimeoutMillis) throws IOException {
        return Sockets.connect(target, connectTimeoutMillis);
    }

    /**
     * Writes {@code text} and reads as much back; an {@link IOException}, not an assertion, when the stream ends
     * first, for the tests that expect a reset connection to fail here.
     */
    private static String roundTrip(Socket s, String text) throws IOException {
        s.getOutputStream().write(ascii(text));
        s.getOutputStream().flush();
        byte[] buf = s.getInputStream().readNBytes(text.length());
        if (buf.length < text.length()) {
            throw new IOException("stream closed after " + buf.length + " bytes");
        }
        return new String(buf, StandardCharsets.US_ASCII);
    }

    @Test
    void relaysLargePayloadWithBackpressure() throws Exception {
        byte[] payload = new byte[10 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        AtomicBoolean readerFailed = new AtomicBoolean();
        try (Socket s = connect()) {
            s.setSoTimeout(30_000);
            byte[][] received = new byte[1][];
            Thread reader = new Thread(() -> {
                try {
                    received[0] = s.getInputStream().readNBytes(payload.length);
                } catch (IOException e) {
                    readerFailed.set(true);
                }
            });
            // exercise snapshot() concurrently while traffic flows
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> snapshotFailure = new AtomicReference<>();
            Thread snapshots = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        TargetSnapshot snap = target.snapshot();
                        assertTrue(snap.bytesUpstream() >= 0 && snap.bytesDownstream() >= 0);
                        target.events();
                    } catch (Throwable t) {
                        snapshotFailure.set(t);
                        return;
                    }
                }
            });
            reader.start();
            snapshots.start();
            OutputStream out = s.getOutputStream();
            for (int off = 0; off < payload.length; off += 64 * 1024) {
                out.write(payload, off, Math.min(64 * 1024, payload.length - off));
            }
            out.flush();
            reader.join(30_000);
            stop.set(true);
            snapshots.join(5_000);
            assertFalse(readerFailed.get(), "reader failed");
            assertNull(snapshotFailure.get(), "snapshot reader failed");
            assertArrayEquals(payload, received[0]);
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> target.connections() == 0);
        TargetSnapshot snap = target.snapshot();
        assertEquals(payload.length, snap.bytesUpstream());
        assertEquals(payload.length, snap.bytesDownstream());
        assertEquals(1, snap.connectionsSeen());
    }

    @Test
    void unavailableRefusesConnectionsAndResetsExistingOnes() throws Exception {
        Socket existing = connect();
        assertEquals("ping", roundTrip(existing, "ping"));
        try (ActiveFault fault = api(target).unavailable()) {
            assertThrows(ConnectException.class, this::connect);
            assertThrows(IOException.class, () -> roundTrip(existing, "dead"));
            assertFalse(target.snapshot().listening());
        }
        assertTrue(target.snapshot().listening());
        try (Socket s = connect()) {
            assertEquals("back", roundTrip(s, "back"));
        }
        existing.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void connectTimeoutDropsNewConnectionsButKeepsExistingOnes() throws Exception {
        Socket existing = connect();
        assertEquals("before", roundTrip(existing, "before"));
        try (ActiveFault fault = api(target).connectTimeout()) {
            long elapsedMillis = millis(() -> assertThrows(SocketTimeoutException.class, () -> connect(1_000)));
            assertTrue(elapsedMillis >= 900, "connect gave up too early: " + elapsedMillis + "ms");
            assertEquals("during", roundTrip(existing, "during"));
        }
        try (Socket s = connect(1_000)) {
            assertEquals("after", roundTrip(s, "after"));
        }
        existing.close();
    }

    @Test
    void hangHoldsDataUntilEnded() throws Exception {
        try (Socket s = connect()) {
            s.setSoTimeout(1_000);
            try (ActiveFault fault = api(target).hangs()) {
                s.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
                assertTrue(s.isConnected() && !s.isClosed());
                assertTrue(target.snapshot().connections().getFirst().held());
            }
            byte[] buf = new byte[5];
            int n = s.getInputStream().read(buf);
            assertEquals("hello", new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    @Test
    void hangAppliesToConnectionsOpenedDuringTheFault() throws Exception {
        try (ActiveFault fault = api(target).hangs(); Socket s = connect()) {
            s.setSoTimeout(500);
            s.getOutputStream().write("x".getBytes(StandardCharsets.UTF_8));
            assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
        }
    }

    @Test
    void diesMidRequestAfterBytes() throws Exception {
        try (Socket s = connect()) {
            byte[] request = new byte[200];
            new Random(1).nextBytes(request);
            try (ActiveFault fault = api(target).diesMidRequest(afterBytes(100))) {
                s.getOutputStream().write(request);
                s.getOutputStream().flush();
                byte[] head = s.getInputStream().readNBytes(100);
                assertEquals(100, head.length);
                assertArrayEquals(java.util.Arrays.copyOf(request, 100), head);
                expectReset(s.getInputStream());
            }
        }
        assertEquals(1, target.snapshot().connectionsReset());
    }

    @Test
    void diesMidRequestDefaultResetsOnFirstResponseByte() throws Exception {
        try (Socket s = connect(); ActiveFault fault = api(target).diesMidRequest()) {
            s.getOutputStream().write("request".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            expectReset(s.getInputStream());
        }
    }

    @Test
    void diesMidRequestResetsWhenTheResponseEndsExactlyOnTheThreshold() throws Exception {
        // upstream sends exactly the threshold, then nothing more: the reset must fire on that boundary buffer,
        // not wait for a following one that never comes. The upstream writes as soon as the connection opens and a
        // fault counts only the bytes it sees, so the fault is applied before connecting.
        try (TestUpstream up = TestUpstream.scripted(new byte[100])) {
            ProxyTarget t = registry.targetFor(TargetName.of("exact"), Upstream.of("127.0.0.1", up.port()), "test.exact.url");
            try (ActiveFault f = api(t).diesMidRequest(afterBytes(100)); Socket s = Sockets.connect(t)) {
                assertEquals(100, s.getInputStream().readNBytes(100).length);
                // pre-fix: no reset is scheduled and the idle upstream sends nothing, so this would time out
                IOException e = assertThrows(IOException.class, () -> {
                    if (s.getInputStream().read() < 0) {
                        throw new IOException("clean EOF");
                    }
                });
                assertTrue(e.getMessage().toLowerCase().contains("reset"), "expected a reset, got: " + e);
            }
        }
    }

    @Test
    void diesMidRequestOnNextWriteNeverReachesUpstream() throws Exception {
        try (Socket s = connect(); ActiveFault fault = api(target).diesMidRequest(onNextWrite())) {
            s.getOutputStream().write("request".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            assertThrows(IOException.class, () -> {
                if (s.getInputStream().read() < 0) {
                    throw new IOException("closed");
                }
            });
        }
        assertEquals(0, target.snapshot().bytesUpstream());
    }

    private static final String STREAM_HEADERS = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
            + "Transfer-Encoding: chunked\r\n\r\n";
    private static final String LAST_CHUNK = "0\r\n\r\n";

    /**
     * One HTTP/1.1 chunk: hex size, CRLF, data, CRLF.
     */
    private static String chunk(String data) {
        return Integer.toHexString(data.length()) + "\r\n" + data + "\r\n";
    }

    private ProxyTarget scriptedTarget(String name, TestUpstream up) {
        return registry.targetFor(TargetName.of(name), Upstream.http("127.0.0.1", up.port()), "test." + name + ".url");
    }

    @Test
    void diesMidRequestAfterHeadersDeliversTheHeadersOnly() throws Exception {
        try (TestUpstream up = TestUpstream.scripted(ascii(STREAM_HEADERS + chunk("data: a\n\n")))) {
            ProxyTarget t = scriptedTarget("headers", up);
            try (ActiveFault f = api(t).diesMidRequest(afterHeaders()); Socket s = Sockets.connect(t)) {
                assertEquals(STREAM_HEADERS, readExactly(s.getInputStream(), STREAM_HEADERS.length()));
                expectReset(s.getInputStream());
            }
            assertEquals(1, t.snapshot().connectionsReset());
        }
    }

    @Test
    void diesMidRequestAfterChunksCutsAChunkedBodyOnAChunkBoundary() throws Exception {
        // the first chunk's data contains a CRLF: chunks must be measured by their size line, not by line breaks
        String first = chunk("one\r\ntwo");
        String second = chunk("three");
        String response = STREAM_HEADERS + first + second + chunk("four") + LAST_CHUNK;
        try (TestUpstream up = TestUpstream.scripted(ascii(response))) {
            ProxyTarget t = scriptedTarget("chunks", up);
            try (ActiveFault f = api(t).diesMidRequest(afterChunks(2)); Socket s = Sockets.connect(t)) {
                String expected = STREAM_HEADERS + first + second;
                assertEquals(expected, readExactly(s.getInputStream(), expected.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void diesMidRequestAfterSseEventsCountsEventsSplitAcrossChunksAndBuffers() throws Exception {
        // the blank line that ends the first event straddles two chunks, delivered by two separate writes
        String firstWrite = STREAM_HEADERS + chunk("data: a\n");
        String secondData = "\ndata: b\n\n";
        try (TestUpstream up = TestUpstream.scripted(ascii(firstWrite), ascii(chunk(secondData) + LAST_CHUNK))) {
            ProxyTarget t = scriptedTarget("sse", up);
            try (ActiveFault f = api(t).diesMidRequest(afterSseEvents(1)); Socket s = Sockets.connect(t)) {
                // everything up to and including the newline that completes event "a": the size line of the
                // second chunk and its first data byte
                String expected = firstWrite + Integer.toHexString(secondData.length()) + "\r\n" + "\n";
                assertEquals(expected, readExactly(s.getInputStream(), expected.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void hangsAfterSseEventsHoldsTheRestOfTheStreamUntilEnded() throws Exception {
        String eventA = chunk("data: a\n\n");
        String response = STREAM_HEADERS + eventA + chunk("data: b\n\n") + chunk("data: c\n\n") + LAST_CHUNK;
        try (TestUpstream up = TestUpstream.scripted(ascii(response))) {
            ProxyTarget t = scriptedTarget("stall", up);
            try (Socket s = Sockets.connect(t)) {
                s.setSoTimeout(1_000);
                // the event ends at its second newline, before the chunk's own trailing CRLF
                String delivered = STREAM_HEADERS + eventA.substring(0, eventA.length() - 2);
                try (ActiveFault f = api(t).hangs(afterSseEvents(1))) {
                    assertEquals(delivered, readExactly(s.getInputStream(), delivered.length()));
                    assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
                    assertTrue(s.isConnected() && !s.isClosed());
                    assertTrue(t.snapshot().connections().getFirst().held());
                }
                String rest = response.substring(delivered.length());
                assertEquals(rest, readExactly(s.getInputStream(), rest.length()));
            }
        }
    }

    @Test
    void diesMidRequestAfterChunksCountsTheChunksOfEachResponse() throws Exception {
        // the first response on the connection is too short to be cut, the one after it is not
        String shortResponse = STREAM_HEADERS + chunk("one") + LAST_CHUNK;
        String delivered = shortResponse + STREAM_HEADERS + chunk("one") + chunk("two");
        try (TestUpstream up = TestUpstream.scripted(ascii(delivered + chunk("three") + LAST_CHUNK))) {
            ProxyTarget t = scriptedTarget("keepalive", up);
            try (ActiveFault f = api(t).diesMidRequest(afterChunks(2)); Socket s = Sockets.connect(t)) {
                assertEquals(delivered, readExactly(s.getInputStream(), delivered.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void diesMidRequestAfterSseEventsCountsOnlyTheEventsOfAnEventStream() throws Exception {
        // a JSON response with a blank line in its body, then the stream on the same connection
        String json = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4\r\n\r\n{\n\n}";
        String eventA = chunk("data: a\n\n");
        String response = json + STREAM_HEADERS + eventA + chunk("data: b\n\n") + LAST_CHUNK;
        try (TestUpstream up = TestUpstream.scripted(ascii(response))) {
            ProxyTarget t = scriptedTarget("json-then-sse", up);
            try (ActiveFault f = api(t).diesMidRequest(afterSseEvents(1)); Socket s = Sockets.connect(t)) {
                // the event ends at its second newline, before the chunk's own trailing CRLF
                String delivered = json + STREAM_HEADERS + eventA.substring(0, eventA.length() - 2);
                assertEquals(delivered, readExactly(s.getInputStream(), delivered.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void diesMidRequestAfterHeadersSkipsAnInterimResponse() throws Exception {
        String delivered = "HTTP/1.1 100 Continue\r\n\r\n" + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n";
        try (TestUpstream up = TestUpstream.scripted(ascii(delivered + "ok"))) {
            ProxyTarget t = scriptedTarget("interim", up);
            try (ActiveFault f = api(t).diesMidRequest(afterHeaders()); Socket s = Sockets.connect(t)) {
                assertEquals(delivered, readExactly(s.getInputStream(), delivered.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void aResponseToHeadIsFollowedWithoutABody() throws Exception {
        // the response to HEAD announces a body that never comes; the response after it must still be found
        String toHead = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n";
        String delivered = STREAM_HEADERS + chunk("one");
        try (TestUpstream up = TestUpstream.answering(ascii(toHead), ascii(delivered + chunk("two") + LAST_CHUNK))) {
            ProxyTarget t = registry.targetFor(TargetName.of("head"), Upstream.http("127.0.0.1", up.port()), "test.head.url");
            try (ActiveFault f = api(t).diesMidRequest(afterChunks(1)); Socket s = Sockets.connect(t)) {
                s.getOutputStream().write(ascii("HEAD /a HTTP/1.1\r\nHost: h\r\n\r\n"));
                s.getOutputStream().flush();
                assertEquals(toHead, readExactly(s.getInputStream(), toHead.length()));
                s.getOutputStream().write(ascii("GET /a HTTP/1.1\r\nHost: h\r\n\r\n"));
                s.getOutputStream().flush();
                assertEquals(delivered, readExactly(s.getInputStream(), delivered.length()));
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void respondsWithReturnsTheStatusAndNeverReachesUpstream() throws Exception {
        try (Socket s = connect(); ActiveFault f = api(target).http().respondsWith(503)) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable\r\n"), response);
        }
        assertEquals(0, target.snapshot().bytesUpstream(), "the request must not reach the upstream");
    }

    @Test
    void requestRecordCarriesTheStatusRespondsWithAnsweredWith() throws Exception {
        try (Socket s = connect(); ActiveFault f = api(target).http().respondsWith(503)) {
            s.getOutputStream().write(ascii("GET /a HTTP/1.1\r\nHost: h\r\n\r\n"));
            s.getOutputStream().flush();
            s.getInputStream().readAllBytes();
        }
        HttpRequestRecord record = target.httpRequests(RequestScope.ALL).getFirst();
        assertEquals(503, record.responseStatus());
        assertNotNull(record.responseTime());
    }

    @Test
    void respondsWithCarriesABodyContentTypeAndHeaders() throws Exception {
        String body = "{\"error\":\"rate limited\"}";
        try (Socket s = connect();
                ActiveFault f = api(target).http().respondsWith(429)
                        .contentType("application/json")
                        .body(body)
                        .header("Retry-After", "30")) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 429"), response);
            assertTrue(response.contains("content-type: application/json"), response);
            assertTrue(response.contains("Retry-After: 30"), response);
            assertTrue(response.contains("content-length: " + body.getBytes(StandardCharsets.UTF_8).length), response);
            assertTrue(response.endsWith(body), response);
        }
    }

    @Test
    void respondsWithForFirstRecoversAfterThatManyRequests() throws Exception {
        try (ActiveFault f = api(target).http().forFirst(2).http().respondsWith(503)) {
            // the first two requests, each on its own connection, get the synthetic response
            for (int i = 0; i < 2; i++) {
                try (Socket s = connect()) {
                    s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().flush();
                    String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 503"), "request " + i + ": " + response);
                }
            }
            // the third is relayed to the echo upstream, which echoes it back unchanged
            try (Socket s = connect()) {
                String request = "GET /recovered HTTP/1.1\r\nHost: x\r\n\r\n";
                s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                String echoed = new String(s.getInputStream().readNBytes(request.length()), StandardCharsets.UTF_8);
                assertEquals(request, echoed);
            }
        }
        assertTrue(target.snapshot().bytesUpstream() > 0, "the recovered request must reach the upstream");
    }

    @Test
    void aFaultStartsWithoutWhatThePreviousOneKeptAboutAConnection() throws Exception {
        String request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n";
        try (Socket s = connect()) {
            // the first fault follows the connection without ever striking
            try (ActiveFault f = api(target).diesMidRequest(afterBytes(1_000))) {
                assertEquals(request, roundTrip(s, request));
            }
            // the second, of another kind, takes over the same connection
            try (ActiveFault f = api(target).http().respondsWith(503)) {
                s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 503"), response);
            }
        }
    }

    @Test
    void slowDelaysRoundTrips() throws Exception {
        try (Socket s = connect()) {
            try (ActiveFault fault = api(target).slow(Duration.ofMillis(500))) {
                long elapsed = millis(() -> assertEquals("slow", roundTrip(s, "slow")));
                assertTrue(elapsed >= 500, "round trip too fast: " + elapsed + "ms");
            }
            long fast = millis(() -> assertEquals("fast", roundTrip(s, "fast")));
            assertTrue(fast < 500);
        }
    }

    @Test
    void dropConnectionsKillsLiveOnesOnly() throws Exception {
        Socket before = connect();
        assertEquals("ok", roundTrip(before, "ok"));
        target.dropConnections();
        assertThrows(IOException.class, () -> roundTrip(before, "dead"));
        try (Socket after = connect()) {
            assertEquals("fresh", roundTrip(after, "fresh"));
        }
        before.close();
    }

    @Test
    void advancedFaultsRelayCorrectly() throws Exception {
        byte[] payload = new byte[64 * 1024];
        new Random(7).nextBytes(payload);
        try (Socket s = connect()) {
            s.setSoTimeout(20_000);
            try (ActiveFault f = api(target).advanced().slice(100, Duration.ofMillis(1), Advanced.Direction.DOWNSTREAM)) {
                s.getOutputStream().write(payload);
                assertArrayEquals(payload, s.getInputStream().readNBytes(payload.length));
            }
            try (ActiveFault f = api(target).advanced().bandwidth(256 * 1024, Advanced.Direction.BOTH)) {
                long elapsed = millis(() -> {
                    s.getOutputStream().write(payload);
                    assertArrayEquals(payload, s.getInputStream().readNBytes(payload.length));
                });
                assertTrue(elapsed >= 400, "64KB at 256KB/s each way should take >= 0.5s, took " + elapsed + "ms");
            }
            try (ActiveFault f = api(target).advanced().latency(Duration.ofMillis(100), Duration.ofMillis(50),
                    Advanced.Direction.UPSTREAM)) {
                long delayed = millis(() -> assertEquals("j", roundTrip(s, "j")));
                assertTrue(delayed >= 100);
            }
        }
    }

    @Test
    void aSecondFaultIsRefusedWhileOneIsActive() {
        ActiveFault hang = api(target).hangs();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> api(target).slow(Duration.ofMillis(10)));
        assertTrue(refused.getMessage().contains("'hangs'"), refused.getMessage());
        assertEquals("hangs", target.snapshot().fault());
        hang.end();
        ActiveFault slow = api(target).slow(Duration.ofMillis(10));
        assertEquals("slow 10ms", target.snapshot().fault());
        assertEquals("hangs", target.snapshot().previousFault());
        hang.end(); // stale handle: must not end the slow fault
        assertEquals("slow 10ms", target.snapshot().fault());
        slow.end();
        assertEquals("none", target.snapshot().fault());
    }

    @Test
    void eventLogIsOrderedAndBounded() throws Exception {
        try (Socket s = connect()) {
            assertEquals("a", roundTrip(s, "a"));
        }
        try (Socket s = connect()) {
            assertEquals("b", roundTrip(s, "b"));
        }
        try (ActiveFault f = api(target).hangs()) {
            // nothing
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> target.connections() == 0);
        // 7 events happened (bound, 2x opened+closed, fault started+ended); only the last 5 are kept
        List<TargetEvent> events = target.events();
        assertEquals(5, events.size(), "ring buffer capacity is 5, got " + events);
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i).time().isBefore(events.get(i - 1).time()));
        }
        List<TargetEvent.Type> types = new ArrayList<>();
        for (TargetEvent e : events) {
            types.add(e.type());
        }
        assertTrue(types.indexOf(TargetEvent.Type.FAULT_STARTED) < types.indexOf(TargetEvent.Type.FAULT_ENDED));
        // the oldest event (LISTENER_BOUND) was evicted
        assertFalse(types.contains(TargetEvent.Type.LISTENER_BOUND));
    }

    @Test
    void closingTheRegistryReleasesItsVertxInstance() {
        assertEquals(1, ProxyRegistry.liveInstances());
        registry.close();
        assertEquals(0, ProxyRegistry.liveInstances());
        assertThrows(IllegalStateException.class,
                () -> registry.targetFor(TargetName.of("x"), Upstream.of("127.0.0.1", 1), "p"));
    }

    @Test
    void countsPlaintextHttpRequestsEvenWhenHeld() throws Exception {
        String req1 = "GET /a?x=1 HTTP/1.1\r\nHost: h\r\n\r\n";
        String req2 = "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\nhi";
        try (Socket s = connect()) {
            assertEquals(req1, roundTrip(s, req1));
            // pipelined in one write, then a body that looks nothing like a request
            assertEquals(req1 + req2, roundTrip(s, req1 + req2));
            try (ActiveFault f = api(target).hangs()) {
                s.getOutputStream().write(req2.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                // a hang stops reading, so the request must stay unseen for as long as we care to watch
                await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                        .until(() -> api(target).traffic().httpRequestsSeen() == 3);
            }
            s.getInputStream().readNBytes(req2.length());
        }
        await().atMost(2, TimeUnit.SECONDS).until(() -> api(target).traffic().httpRequestsSeen() == 4);
        List<String> lines = new ArrayList<>();
        for (HttpRequestRecord r : api(target).traffic().httpRequests()) {
            lines.add(r.method() + " " + r.target());
        }
        assertEquals(List.of("GET /a?x=1", "GET /a?x=1", "POST /b", "POST /b"), lines);
    }

    @Test
    void requestRecordCarriesTheResponseStatus() throws Exception {
        try (TestUpstream upstream = TestUpstream.answering(ascii("HTTP/1.1 204 No Content\r\n\r\n"))) {
            ProxyTarget proxy = registry.targetFor(TargetName.of("no-content"), Upstream.http("127.0.0.1", upstream.port()),
                    "test.no-content.url");
            try (Socket s = Sockets.connect(proxy)) {
                s.getOutputStream().write(ascii("DELETE /a HTTP/1.1\r\nHost: h\r\n\r\n"));
                s.getOutputStream().flush();
                readHead(s.getInputStream());
            }
            HttpRequestRecord record = proxy.httpRequests(RequestScope.ALL).getFirst();
            assertEquals(204, record.responseStatus());
            assertNotNull(record.responseTime());
        }
    }

    @Test
    void countsRequestsByFollowingEachMessage() throws Exception {
        // bodies that contain what looks like a request, one sized by Content-Length and one chunked, each with a
        // request pipelined right behind it
        String looksLikeARequest = "x\r\n\r\nGET /fake HTTP/1.1\r\n\r\n";
        String sized = "POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: " + looksLikeARequest.length() + "\r\n\r\n"
                + looksLikeARequest;
        String chunked = "POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk(looksLikeARequest)
                + LAST_CHUNK;
        String pipelined = sized + "GET /b HTTP/1.1\r\nHost: h\r\n\r\n" + chunked + "GET /d HTTP/1.1\r\nHost: h\r\n\r\n";
        try (Socket s = connect()) {
            s.setTcpNoDelay(true);
            assertEquals(pipelined, roundTrip(s, pipelined));
            // a request line that arrives in two pieces
            String split = "GET /e HTTP/1.1\r\nHost: h\r\n\r\n";
            s.getOutputStream().write(ascii(split.substring(0, 9)));
            s.getOutputStream().flush();
            Thread.sleep(100);
            s.getOutputStream().write(ascii(split.substring(9)));
            s.getOutputStream().flush();
            assertEquals(split, readExactly(s.getInputStream(), split.length()));
        }
        List<String> lines = new ArrayList<>();
        for (HttpRequestRecord r : api(target).traffic().httpRequests()) {
            lines.add(r.method() + " " + r.target());
        }
        assertEquals(List.of("POST /a", "GET /b", "POST /c", "GET /d", "GET /e"), lines);
    }

    @Test
    void anAddressGetsOneTargetHoweverOftenItIsLookedUp() {
        Upstream upstream = Upstream.http("127.0.0.1", echo.port());
        assertEquals(target, registry.targetFor(TargetName.of("echo"), upstream, "test.other.url"));
    }

    @Test
    void aSecondAddressUnderATakenNameIsNumbered() {
        Upstream elsewhere = Upstream.of("127.0.0.1", 1);
        ProxyTarget second = registry.targetFor(TargetName.of("echo"), elsewhere, "test.echo.url");
        assertEquals("echo#2", second.name());
        assertEquals(second, registry.targetFor(TargetName.of("echo"), elsewhere, "test.echo.url"));
    }

    @Test
    void anAddressThatIsAProxyIsThatProxyNotAProxyOfIt() {
        // what a value looks like when it was already rewritten under another key
        Upstream rewritten = Upstream.http(AddressUtil.PROXY_HOST, target.port());
        assertEquals(target, registry.targetFor(TargetName.of("other"), rewritten, "test.other.url"));
        assertEquals(1, registry.targets().size());
    }

    @Test
    void upstreamConnectFailureCountsAsAConnectFailureNotAReset() throws Exception {
        int deadPort;
        try (ServerSocket ss = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            deadPort = ss.getLocalPort();
        }
        ProxyTarget dead = registry.targetFor(TargetName.of("dead"), Upstream.of("127.0.0.1", deadPort), "test.dead.url");
        try (Socket s = Sockets.connect(dead)) {
            // the proxy cannot reach the upstream, so it resets the client connection
            assertThrows(IOException.class, () -> {
                if (s.getInputStream().read() < 0) {
                    throw new IOException("closed");
                }
            });
        }
        await().atMost(2, TimeUnit.SECONDS).until(() -> dead.snapshot().upstreamConnectFailures() == 1);
        // a connection that never reached the upstream is not a connection a fault reset
        assertEquals(0, dead.snapshot().connectionsReset());
    }

    @Test
    void unavailableThenReuseKeepsTheSamePort() {
        int port = target.port();
        try (ActiveFault f = api(target).unavailable()) {
            // refusing: the listener is gone, but the port stays reserved for it
        }
        assertEquals(port, target.port());
    }

    @Test
    void closesAfterBytesEndsTheStreamCleanly() throws Exception {
        try (TestUpstream up = TestUpstream.scripted(ascii("0123456789"), ascii("abcdef"))) {
            ProxyTarget t = scriptedTarget("closing", up);
            try (ActiveFault f = api(t).closes(afterBytes(5)); Socket s = Sockets.connect(t)) {
                assertEquals("01234", readExactly(s.getInputStream(), 5));
                // a clean end of stream, not a reset and not a timeout
                assertEquals(-1, s.getInputStream().read());
            }
            await().atMost(2, TimeUnit.SECONDS).until(() -> t.connections() == 0);
            assertEquals(0, t.snapshot().connectionsReset());
        }
    }

    @Test
    void closesOnNextWriteNeverReachesUpstream() throws Exception {
        try (Socket s = connect(); ActiveFault f = api(target).closes(onNextWrite())) {
            s.getOutputStream().write(ascii("request"));
            s.getOutputStream().flush();
            assertEquals(-1, s.getInputStream().read());
        }
        assertEquals(0, target.snapshot().bytesUpstream());
    }

    @Test
    void closesAfterHeadersDeliversTheHeadersThenEnds() throws Exception {
        try (TestUpstream up = TestUpstream.scripted(ascii(STREAM_HEADERS + chunk("data: a\n\n")))) {
            ProxyTarget t = scriptedTarget("headers-only", up);
            try (ActiveFault f = api(t).closes(afterHeaders()); Socket s = Sockets.connect(t)) {
                assertEquals(STREAM_HEADERS, readExactly(s.getInputStream(), STREAM_HEADERS.length()));
                assertEquals(-1, s.getInputStream().read());
            }
        }
    }

    @Test
    void respondsWithBodyOfLengthStreamsThatManyBytes() throws Exception {
        long length = 5_000_000;
        try (Socket s = connect(); ActiveFault f = api(target).http().respondsWith(200).bodyOfLength(length)) {
            s.getOutputStream().write(ascii("GET / HTTP/1.1\r\nHost: x\r\n\r\n"));
            s.getOutputStream().flush();
            String head = readHead(s.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 200"), head);
            assertTrue(head.contains("content-length: " + length), head);
            long received = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.getInputStream().read(buf)) >= 0) {
                received += n;
            }
            assertEquals(length, received);
        }
    }

    /**
     * Sends one request on a fresh connection and tells whether the fault answered it or the echo upstream did.
     */
    private String answered(String path) throws IOException {
        String request = "GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n";
        try (Socket s = connect()) {
            s.getOutputStream().write(ascii(request));
            s.getOutputStream().flush();
            String response = new String(s.getInputStream().readNBytes(request.length()), StandardCharsets.US_ASCII);
            return response.equals(request) ? "relayed" : response.substring(0, "HTTP/1.1 503".length());
        }
    }

    @Test
    void respondsWithEveryNthRequest() throws Exception {
        try (ActiveFault f = api(target).http().every(2).http().respondsWith(503)) {
            assertEquals("HTTP/1.1 503", answered("/1"));
            assertEquals("relayed", answered("/2"));
            assertEquals("HTTP/1.1 503", answered("/3"));
            assertEquals("relayed", answered("/4"));
        }
    }

    @Test
    void respondsWithAfterNRequests() throws Exception {
        try (ActiveFault f = api(target).http().after(2).http().respondsWith(503)) {
            assertEquals("relayed", answered("/1"));
            assertEquals("relayed", answered("/2"));
            assertEquals("HTTP/1.1 503", answered("/3"));
            assertEquals("HTTP/1.1 503", answered("/4"));
        }
    }

    @Test
    void theRequestSelectionsAreExclusive() {
        IllegalStateException twice = assertThrows(IllegalStateException.class,
                () -> api(target).http().forFirst(1).http().every(2));
        assertTrue(twice.getMessage().contains("first 1"), twice.getMessage());
        assertThrows(IllegalArgumentException.class, () -> api(target).http().every(0));
    }

    @Test
    void awaitConnectionsWaitsForThePoolToFill() throws Exception {
        try (Socket first = connect()) {
            Thread opener = new Thread(() -> {
                try (Socket second = connect()) {
                    Thread.sleep(300);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            opener.start();
            api(target).traffic().awaitConnections(2, Duration.ofSeconds(2));
            assertTrue(api(target).traffic().connections() >= 2);
            opener.join();
        }
        AssertionError late = assertThrows(AssertionError.class,
                () -> api(target).traffic().awaitConnections(5, Duration.ofMillis(200)));
        assertTrue(late.getMessage().contains("at least 5"), late.getMessage());
    }

    @Test
    void awaitHeldConnectionsCountsTheConnectionsAFaultStopped() throws Exception {
        try (Socket busy = connect(); Socket idle = connect(); ActiveFault f = api(target).hangs(onNextWrite())) {
            api(target).traffic().awaitConnections(2, Duration.ofSeconds(2));
            // an idle pooled connection is open but not held; one that sends a request is held at that write
            assertThrows(AssertionError.class, () -> api(target).traffic().awaitHeldConnections(1, Duration.ofMillis(200)));
            busy.getOutputStream().write(ascii("GET /q HTTP/1.1\r\n"));
            busy.getOutputStream().flush();
            api(target).traffic().awaitHeldConnections(1, Duration.ofSeconds(2));
            assertThrows(AssertionError.class, () -> api(target).traffic().awaitHeldConnections(2, Duration.ofMillis(200)));
        }
    }
}
