package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class JdbcUrlRewriterTest {

    private final List<String> seen = new ArrayList<>();
    private final JdbcUrlRewriter rewriter = new JdbcUrlRewriter();

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
    void jdbcUrls() {
        assertEquals("jdbc:postgresql://127.0.0.1:40000/db?ssl=false",
                rewrite("jdbc:postgresql://db.internal:5433/db?ssl=false"));
        assertEquals(List.of("db.internal:5433"), seen);
        assertEquals("jdbc:postgresql://127.0.0.1:40000/db", rewrite("jdbc:postgresql://localhost/db"));
        assertEquals(List.of("localhost:5432"), seen);
        assertEquals("jdbc:mysql://127.0.0.1:40000/db", rewrite("jdbc:mysql://h/db"));
        assertEquals(List.of("h:3306"), seen);
        assertEquals("jdbc:sqlserver://127.0.0.1:40000;databaseName=x", rewrite("jdbc:sqlserver://sql;databaseName=x"));
        assertEquals("jdbc:oracle:thin:@127.0.0.1:40000/XE", rewrite("jdbc:oracle:thin:@ora:1522/XE"));
        assertEquals(List.of("ora:1522"), seen);
        assertEquals("jdbc:oracle:thin:@//127.0.0.1:40000/XE", rewrite("jdbc:oracle:thin:@//ora/XE"));
        assertEquals("jdbc:h2:tcp://127.0.0.1:40000/~/test", rewrite("jdbc:h2:tcp://h:9093/~/test"));
        assertEquals("jdbc:postgresql://127.0.0.1:40000/db", rewrite("jdbc:postgresql://[::1]:5432/db"));
        assertEquals(List.of("[::1]:5432"), seen);
    }

    @Test
    void unsupportedFormsAreLeftAlone() {
        assertNull(rewrite("jdbc:h2:mem:test"));
        assertNull(rewrite("jdbc:sqlite:/tmp/x.db"));
        assertNull(rewrite("jdbc:postgresql://a:1,b:2/db"));
    }
}
