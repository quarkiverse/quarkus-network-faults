package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.ascii;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.expectReset;
import static io.quarkiverse.resiliencytesting.runtime.internal.proxy.Sockets.readHead;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.runtime.internal.TestTargets;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class ProxyCloseTest {

    private static final byte[] REQUEST = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    private final ProxyRegistry registry = new ProxyRegistry();

    @AfterEach
    void close() {
        registry.close();
    }

    private ProxyTarget target(TestUpstream upstream) {
        return registry.targetFor(TargetName.of("closing"), Upstream.http("127.0.0.1", upstream.port()), "test.url");
    }

    private static String response(int bodySize) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + bodySize + "\r\nConnection: close\r\n\r\n" + "x".repeat(bodySize);
    }

    @ParameterizedTest
    @CsvSource({ "none,65536", "latency,65536", "bandwidth,65536", "slice,65536", "latency,2097152" })
    void normalCloseFollowsAllResponseBytes(String kind, int bodySize) throws Exception {
        String response = response(bodySize);
        try (TestUpstream upstream = TestUpstream.answeringAndClosing(ascii(response))) {
            ProxyTarget proxy = target(upstream);
            Target api = TestTargets.of(proxy);
            try (ActiveFault fault = switch (kind) {
                case "none" -> () -> {
                };
                case "latency" -> api.advanced().latency(Duration.ofMillis(150), Duration.ZERO, Advanced.Direction.DOWNSTREAM);
                case "bandwidth" -> api.advanced().bandwidth(256 * 1024, Advanced.Direction.DOWNSTREAM);
                case "slice" -> api.advanced().slice(512, Duration.ofMillis(1), Advanced.Direction.DOWNSTREAM);
                default -> throw new IllegalArgumentException(kind);
            }; Socket client = Sockets.connect(proxy)) {
                client.setSoTimeout(10_000);
                client.getOutputStream().write(REQUEST);
                assertArrayEquals(ascii(response), client.getInputStream().readAllBytes());
                assertEquals(response.length(), proxy.snapshot().bytesDownstream());
                await().until(() -> proxy.connections() == 0);
            }
        }
    }

    @Test
    void explicitResetDiscardsDelayedResponseImmediately() throws Exception {
        try (TestUpstream upstream = TestUpstream.answeringAndClosing(ascii(response(5)))) {
            ProxyTarget proxy = target(upstream);
            try (ActiveFault fault = TestTargets.of(proxy).advanced().latency(Duration.ofSeconds(10), Duration.ZERO,
                    Advanced.Direction.DOWNSTREAM); Socket client = Sockets.connect(proxy)) {
                client.getOutputStream().write(REQUEST);
                await().until(() -> !proxy.httpRequests(RequestScope.ALL).isEmpty()
                        && proxy.httpRequests(RequestScope.ALL).getFirst().responseTime() != null);
                proxy.dropConnections();
                expectReset(client.getInputStream());
                assertEquals(0, proxy.connections());
            }
        }
    }

    @Test
    void hangingResponseIsReleasedBeforeNormalClose() throws Exception {
        try (TestUpstream upstream = TestUpstream.answeringAndClosing(ascii(response(5)))) {
            ProxyTarget proxy = target(upstream);
            try (ActiveFault fault = TestTargets.of(proxy).hangs(Moment.Http.afterHeaders());
                    Socket client = Sockets.connect(proxy)) {
                client.getOutputStream().write(REQUEST);
                readHead(client.getInputStream());
                client.setSoTimeout(200);
                assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
                fault.end();
                client.setSoTimeout(2_000);
                assertEquals("xxxxx", new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
            }
        }
    }
}
