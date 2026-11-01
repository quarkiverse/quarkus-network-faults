package io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class ReactiveUrlRewriterTest {

    private final List<String> seen = new ArrayList<>();
    private final ReactiveUrlRewriter rewriter = new ReactiveUrlRewriter();

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
    void reactiveUrls() {
        assertEquals("vertx-reactive:postgresql://127.0.0.1:40000/quarkus",
                rewrite("vertx-reactive:postgresql://localhost:5432/quarkus"));
        assertEquals("postgresql://user:pw@127.0.0.1:40000/db?x=1", rewrite("postgresql://user:pw@db:5432/db?x=1"));
        assertEquals("mysql://127.0.0.1:40000/db,mysql://127.0.0.1:40001/db", rewrite("mysql://a/db, mysql://b:3307/db"));
        assertEquals(List.of("a:3306", "b:3307"), seen);
        assertEquals("oracle:thin:@127.0.0.1:40000/XE", rewrite("oracle:thin:@ora/XE"));
    }
}
