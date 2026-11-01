package io.quarkiverse.resiliencytesting.grpc.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class GrpcClientAddressRewriterTest {

    private static final String PREFIX = "quarkus.grpc.clients.hello.";

    private final List<String> seen = new ArrayList<>();
    private final GrpcClientAddressRewriter rewriter = new GrpcClientAddressRewriter();

    private String rewrite(String attribute, String value, Map<String, String> others) {
        AddressRewriter.Context context = new AddressRewriter.Context() {
            @Override
            public String property() {
                return PREFIX + attribute;
            }

            @Override
            public int proxyPort(Upstream upstream) {
                seen.add(upstream.host() + ":" + upstream.port());
                return 40000;
            }

            @Override
            public String neighbour(String property) {
                return others.get(property);
            }
        };
        return rewriter.rewrite(value, context);
    }

    @Test
    void hostAndPortAgreeOnOneProxy() {
        assertEquals("127.0.0.1", rewrite("host", "greeter.internal", Map.of(PREFIX + "port", "9000")));
        assertEquals("40000", rewrite("port", "9000", Map.of(PREFIX + "host", "greeter.internal")));
        assertEquals(List.of("greeter.internal:9000", "greeter.internal:9000"), seen);
    }

    @Test
    void testPortWinsOverPortInTests() {
        assertEquals("127.0.0.1", rewrite("host", "greeter", Map.of(PREFIX + "port", "9000", PREFIX + "test-port", "8081")));
        assertEquals("40000", rewrite("test-port", "8081", Map.of(PREFIX + "host", "greeter", PREFIX + "port", "9000")));
        assertEquals("40000", rewrite("port", "9000", Map.of(PREFIX + "host", "greeter", PREFIX + "test-port", "8081")));
        assertEquals(List.of("greeter:8081", "greeter:8081", "greeter:8081"), seen);
    }

    @Test
    void defaultsFillTheMissingHalf() {
        assertEquals("40000", rewrite("port", "9000", Map.of()));
        assertEquals("127.0.0.1", rewrite("host", "h", Map.of()));
        assertEquals(List.of("localhost:9000", "h:8080"), seen);
    }

    @Test
    void anUpstreamOnTheLoopbackAddressIsProxiedToo() {
        assertEquals("127.0.0.1", rewrite("host", "127.0.0.1", Map.of(PREFIX + "port", "9000")));
        assertEquals("40000", rewrite("port", "9000", Map.of(PREFIX + "host", "127.0.0.1")));
        assertEquals(List.of("127.0.0.1:9000", "127.0.0.1:9000"), seen);
    }
}
