package io.quarkiverse.resiliencytesting.redis.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class RedisHostsRewriterTest {

    private final List<String> seen = new ArrayList<>();
    private final RedisHostsRewriter rewriter = new RedisHostsRewriter();

    private final AddressRewriter.Context context = new AddressRewriter.Context() {
        @Override
        public String property() {
            return "p";
        }

        @Override
        public int proxyPort(Upstream upstream) {
            seen.add(upstream.host() + ":" + upstream.port());
            return 40000 + seen.size() - 1;
        }

        @Override
        public String neighbour(String property) {
            return null;
        }
    };

    private String rewrite(String value) {
        seen.clear();
        return rewriter.rewrite(value, context);
    }

    @Test
    void redisUris() {
        assertEquals("redis://127.0.0.1:40000", rewrite("redis://localhost:32771"));
        assertEquals(List.of("localhost:32771"), seen);
        assertEquals("redis://127.0.0.1:40000", rewrite("redis://cache.internal"));
        assertEquals(List.of("cache.internal:6379"), seen);
        assertEquals("rediss://user:secret@127.0.0.1:40000/2?db=3", rewrite("rediss://user:secret@cache:6380/2?db=3"));
        assertEquals(List.of("cache:6380"), seen);
        assertEquals("valkey://127.0.0.1:40000", rewrite("valkey://v"));
    }

    @Test
    void severalHostsBecomeSeveralProxies() {
        assertEquals("redis://127.0.0.1:40000,redis://127.0.0.1:40001", rewrite("redis://a:7000, redis://b:7001"));
        assertEquals(List.of("a:7000", "b:7001"), seen);
    }

    @Test
    void unixSocketsAreLeftAlone() {
        assertNull(rewrite("unix:///var/run/redis.sock"));
    }
}
