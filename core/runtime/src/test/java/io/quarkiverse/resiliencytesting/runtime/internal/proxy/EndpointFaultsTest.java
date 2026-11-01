package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static io.quarkiverse.resiliencytesting.Moment.Http.afterHeaders;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.ascii;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.expectReset;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.millis;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.readHead;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.Endpoint;
import io.quarkiverse.resiliencytesting.HttpRequestRecord;
import io.quarkiverse.resiliencytesting.Traffic;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultTracker;
import io.quarkiverse.resiliencytesting.runtime.internal.TestTargets;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;

/**
 * Faults on the requests an endpoint covers: on a connection that is kept alive, the requests it covers are faulted
 * and the others, before and after them, are relayed untouched.
 */
class EndpointFaultsTest {

    private static final String RESERVE = "/api/inventory/reserve";
    private static final String STOCK = "/api/inventory/stock";

    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    private HttpServer upstream;
    private ProxyRegistry registry;
    private ProxyTarget target;

    @BeforeEach
    void setUp() {
        // answers every request with its own method and path
        upstream = vertx.createHttpServer()
                .requestHandler(request -> request.response().end(request.method() + " " + request.path()))
                .listen(0, "127.0.0.1").await();
        registry = new ProxyRegistry(5);
        target = registry.targetFor(TargetName.of("inventory"), Upstream.http("127.0.0.1", upstream.actualPort()),
                "test.inventory.url");
    }

    @AfterEach
    void tearDown() {
        registry.close();
        vertx.close().await();
    }

    /**
     * The reserve requests, named without the base path the client's URL would carry.
     */
    private Endpoint reserve() {
        return TestTargets.of(target).http().requests("post", "/inventory/reserve");
    }

    private Endpoint stock() {
        return TestTargets.of(target).http().requests("GET", "/inventory/stock");
    }

    private Socket connect() throws IOException {
        return Sockets.connect(target);
    }

    private static void send(Socket s, String method, String path) throws IOException {
        s.getOutputStream().write(ascii(method + " " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n"));
        s.getOutputStream().flush();
    }

    /**
     * Sends a request and returns the status and the body of its response.
     */
    private static String call(Socket s, String method, String path) throws IOException {
        send(s, method, path);
        return readResponse(s.getInputStream());
    }

    private static String readResponse(InputStream in) throws IOException {
        String head = readHead(in);
        String status = head.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3);
        return status + " " + readBody(in, head);
    }

    private static String readBody(InputStream in, String head) throws IOException {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                int length = Integer.parseInt(line.substring("content-length:".length()).trim());
                byte[] body = in.readNBytes(length);
                assertEquals(length, body.length, "stream ended in the body");
                return new String(body, StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("no content-length in " + head);
    }

    @Test
    void hangsHoldsTheRequestsItCoversUntilEnded() throws Exception {
        try (Socket s = connect()) {
            try (ActiveFault f = reserve().hangs()) {
                assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
                s.setSoTimeout(500);
                send(s, "POST", RESERVE);
                assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
                try (Socket other = connect()) {
                    assertEquals("200 GET " + STOCK, call(other, "GET", STOCK));
                }
            }
            s.setSoTimeout(Sockets.READ_TIMEOUT_MILLIS);
            assertEquals("200 POST " + RESERVE, readResponse(s.getInputStream()));
        }
    }

    @Test
    void hangsAtAMomentHoldsTheResponsesOfTheRequestsItCovers() throws Exception {
        try (Socket s = connect()) {
            try (ActiveFault f = reserve().hangs(afterHeaders())) {
                assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
                send(s, "POST", RESERVE);
                String head = readHead(s.getInputStream());
                assertTrue(head.startsWith("HTTP/1.1 200"), head);
                s.setSoTimeout(500);
                assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
                s.setSoTimeout(Sockets.READ_TIMEOUT_MILLIS);
                f.end();
                assertEquals("POST " + RESERVE, readBody(s.getInputStream(), head));
            }
        }
    }

    @Test
    void diesMidRequestResetsTheConnectionOfARequestItCovers() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().diesMidRequest()) {
            assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
            send(s, "POST", RESERVE);
            expectReset(s.getInputStream());
        }
        assertEquals(1, target.snapshot().connectionsReset());
    }

    @Test
    void diesMidRequestAtAMomentCountsTheResponsesOfTheRequestsItCoversOnly() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().diesMidRequest(afterHeaders())) {
            // a response to another request has headers too, and is relayed whole
            assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
            send(s, "POST", RESERVE);
            assertTrue(readHead(s.getInputStream()).startsWith("HTTP/1.1 200"));
            expectReset(s.getInputStream());
        }
    }

    @Test
    void aRequestLineThatArrivesInPiecesIsStillRecognised() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().diesMidRequest()) {
            s.setTcpNoDelay(true);
            String request = "POST " + RESERVE + " HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n";
            s.getOutputStream().write(ascii(request.substring(0, 9)));
            s.getOutputStream().flush();
            Thread.sleep(100);
            s.getOutputStream().write(ascii(request.substring(9)));
            s.getOutputStream().flush();
            expectReset(s.getInputStream());
        }
    }

    @Test
    void slowDelaysTheRequestsItCoversOnly() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().slow(Duration.ofMillis(300))) {
            long other = millis(() -> assertEquals("200 GET " + STOCK, call(s, "GET", STOCK)));
            assertTrue(other < 300, "another request was delayed: " + other + "ms");
            long covered = millis(() -> assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE)));
            assertTrue(covered >= 300, "the request was not delayed: " + covered + "ms");
        }
    }

    @Test
    void advancedFaultsActOnTheRequestsItCoversOnly() throws Exception {
        try (Socket s = connect();
                ActiveFault f = reserve().advanced().latency(Duration.ofMillis(300), Duration.ZERO,
                        Advanced.Direction.DOWNSTREAM)) {
            long other = millis(() -> assertEquals("200 GET " + STOCK, call(s, "GET", STOCK)));
            assertTrue(other < 300, "another request was delayed: " + other + "ms");
            long covered = millis(() -> assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE)));
            assertTrue(covered >= 300, "the response was not delayed: " + covered + "ms");
        }
    }

    @Test
    void respondsWithAnswersTheRequestsItCoversAndRelaysTheOthers() throws Exception {
        try (ActiveFault f = reserve().http().respondsWith(503)) {
            try (Socket s = connect()) {
                assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
                assertEquals("503 ", call(s, "POST", RESERVE));
            }
            // the synthetic response closed that connection; the next one is relayed as usual
            try (Socket s = connect()) {
                assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
            }
        }
    }

    @Test
    void theRequestsOfAnotherMethodOrPathAreNotCovered() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().diesMidRequest()) {
            assertEquals("200 GET " + RESERVE, call(s, "GET", RESERVE));
            assertEquals("200 POST " + RESERVE + "s", call(s, "POST", RESERVE + "s"));
            assertEquals("200 POST /reserve", call(s, "POST", "/reserve"));
        }
    }

    @Test
    void faultsOnDifferentRequestsOfATargetAreActiveTogether() throws Exception {
        try (ActiveFault answered = reserve().http().respondsWith(503); ActiveFault reset = stock().diesMidRequest()) {
            try (Socket s = connect()) {
                send(s, "GET", STOCK);
                expectReset(s.getInputStream());
            }
            try (Socket s = connect()) {
                assertEquals("200 GET /api/inventory/other", call(s, "GET", "/api/inventory/other"));
                assertEquals("503 ", call(s, "POST", RESERVE));
            }
            assertEquals("respondsWith 503 for POST /inventory/reserve, diesMidRequest after 0 response byte(s) for "
                    + "GET /inventory/stock", target.currentFault());

            // ending one leaves the other in place
            answered.end();
            try (Socket s = connect()) {
                assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE));
                send(s, "GET", STOCK);
                expectReset(s.getInputStream());
            }
        }
    }

    @Test
    void aHangOnSomeRequestsReleasesItsOwnConnectionsOnly() throws Exception {
        try (Socket reserving = connect(); Socket streaming = connect()) {
            ActiveFault reserveHangs = reserve().hangs();
            try (ActiveFault stockHangs = stock().hangs()) {
                reserving.setSoTimeout(500);
                streaming.setSoTimeout(500);
                send(reserving, "POST", RESERVE);
                send(streaming, "GET", STOCK);
                assertThrows(SocketTimeoutException.class, () -> reserving.getInputStream().read());
                assertThrows(SocketTimeoutException.class, () -> streaming.getInputStream().read());

                reserveHangs.end();
                reserving.setSoTimeout(Sockets.READ_TIMEOUT_MILLIS);
                assertEquals("200 POST " + RESERVE, readResponse(reserving.getInputStream()));
                assertThrows(SocketTimeoutException.class, () -> streaming.getInputStream().read());
            }
            streaming.setSoTimeout(Sockets.READ_TIMEOUT_MILLIS);
            assertEquals("200 GET " + STOCK, readResponse(streaming.getInputStream()));
        }
    }

    @Test
    void whereTwoEndpointsOverlapTheFaultAppliedFirstActs() throws Exception {
        Endpoint anyAction = TestTargets.of(target).http().requests("POST", "/inventory/{action}");
        try (ActiveFault answered = anyAction.http().respondsWith(503);
                ActiveFault reset = reserve().diesMidRequest();
                Socket s = connect()) {
            assertEquals("503 ", call(s, "POST", RESERVE));
        }
    }

    @Test
    void faultsThatWouldShareTheirRequestsAreRefused() {
        try (ActiveFault f = reserve().hangs()) {
            IllegalStateException same = assertThrows(IllegalStateException.class, () -> reserve().slow(Duration.ofSeconds(1)));
            assertTrue(same.getMessage().contains("'hangs for POST /inventory/reserve' fault active on the same requests"),
                    same.getMessage());
            IllegalStateException whole = assertThrows(IllegalStateException.class,
                    () -> TestTargets.of(target).diesMidRequest());
            assertTrue(whole.getMessage().contains("before applying a fault to the whole target"), whole.getMessage());
        }
        try (ActiveFault f = TestTargets.of(target).hangs()) {
            IllegalStateException some = assertThrows(IllegalStateException.class, () -> reserve().hangs());
            assertTrue(some.getMessage().contains("A fault on the whole target has it to itself"), some.getMessage());
        }
        assertEquals("none", target.currentFault());
    }

    @Test
    void anEndpointCountsTheRequestsItCoversSinceTheStart() throws Exception {
        try (Socket s = connect()) {
            call(s, "POST", RESERVE + "?item=a");
            call(s, "GET", STOCK);
            call(s, "POST", RESERVE + "?item=b");
        }
        // the endpoint is named after the requests were sent, and counts them all the same
        Traffic reserved = reserve().traffic();
        assertEquals(2, reserved.httpRequestsSeen());
        List<String> lines = new ArrayList<>();
        for (HttpRequestRecord r : reserved.httpRequests()) {
            lines.add(r.method() + " " + r.target());
        }
        assertEquals(List.of("POST " + RESERVE + "?item=a", "POST " + RESERVE + "?item=b"), lines);
        assertEquals(3, TestTargets.of(target).traffic().httpRequestsSeen());
    }

    @Test
    void closesEndsTheRequestsItCoversCleanlyAndRelaysTheOthers() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().closes()) {
            assertEquals("200 GET " + STOCK, call(s, "GET", STOCK));
            send(s, "POST", RESERVE);
            assertEquals(-1, s.getInputStream().read());
        }
        assertEquals(0, target.snapshot().connectionsReset());
    }

    @Test
    void everyNthRequestCanBeSlowTheRestAreNot() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().http().every(2).slow(Duration.ofMillis(300))) {
            long first = millis(() -> call(s, "POST", RESERVE));
            long second = millis(() -> call(s, "POST", RESERVE));
            long third = millis(() -> call(s, "POST", RESERVE));
            long other = millis(() -> call(s, "GET", STOCK));
            assertTrue(first >= 300 && third >= 300, "the 1st and 3rd should be slow: " + first + ", " + third);
            assertTrue(second < 300 && other < 300, "the 2nd and another path should not: " + second + ", " + other);
        }
    }

    @Test
    void requestsAfterTheFirstOnesHang() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().http().after(1).hangs()) {
            assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE));
            s.setSoTimeout(500);
            send(s, "POST", RESERVE);
            assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read());
        }
    }

    @Test
    void theFirstRequestIsClosedTheNextOneGoesThrough() throws Exception {
        try (ActiveFault f = reserve().http().forFirst(1).closes()) {
            try (Socket s = connect()) {
                send(s, "POST", RESERVE);
                assertEquals(-1, s.getInputStream().read());
            }
            try (Socket s = connect()) {
                assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE));
            }
        }
    }

    @Test
    void aCountingEndpointThatTookNoRequestFailsTheGuard() throws Exception {
        FaultTracker tracker = new FaultTracker(List.of());
        try (ActiveFault f = TestTargets.guarded(tracker, target).http().after(1).http().respondsWith(503);
                Socket s = connect()) {
            // one request: relayed as the first, so the fault acted on nothing
            assertEquals("200 POST " + RESERVE, call(s, "POST", RESERVE));
        }
        List<String> violations = tracker.takeViolations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains("'respondsWith 503 for after the first 1'"), violations.getFirst());
        assertTrue(violations.getFirst().contains("no request the fault selected went through"), violations.getFirst());
    }

    @Test
    void requestsAreRecordedWithHeadersBodyFingerprintAndResponseTime() throws Exception {
        try (Socket s = connect()) {
            String body = "{\"item\":\"widget\"}";
            s.getOutputStream().write(ascii("POST " + RESERVE + " HTTP/1.1\r\nHost: x\r\nIdempotency-Key: k-1\r\n"
                    + "Accept: text/plain\r\nAccept: */*\r\nContent-Length: " + body.length() + "\r\n\r\n" + body));
            s.getOutputStream().flush();
            assertEquals("200 POST " + RESERVE, readResponse(s.getInputStream()));
        }
        List<HttpRequestRecord> requests = TestTargets.of(target).traffic().httpRequests();
        HttpRequestRecord r = requests.getLast();
        assertEquals("k-1", r.header("idempotency-key"));
        assertEquals(List.of("text/plain", "*/*"), r.headers().get("Accept"));
        assertEquals(List.of("Host", "Idempotency-Key", "Accept", "Content-Length"), List.copyOf(r.headers().keySet()));
        // SHA-256 of the body, as sha256sum prints it
        assertEquals(sha256("{\"item\":\"widget\"}"), r.bodyFingerprint());
        assertTrue(r.responseTime() != null && !r.responseTime().isNegative(), String.valueOf(r.responseTime()));
    }

    @Test
    void aRequestWithoutABodyIsRecordedWithoutAFingerprint() throws Exception {
        try (Socket s = connect(); ActiveFault f = reserve().http().respondsWith(503)) {
            assertEquals("503 ", call(s, "POST", RESERVE));
        }
        HttpRequestRecord r = TestTargets.of(target).traffic().httpRequests().getLast();
        assertNull(r.bodyFingerprint());
        assertEquals(503, r.responseStatus(), "respondsWith answered it");
    }

    private static String sha256(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(ascii(text));
        return HexFormat.of().formatHex(hash);
    }
}
