package io.quarkiverse.resiliencytesting.mongodb.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class MongoConnectionStringRewriterTest {

    private final List<String> seen = new ArrayList<>();
    private final MongoConnectionStringRewriter rewriter = new MongoConnectionStringRewriter();

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
    void singleHost() {
        assertEquals("mongodb://127.0.0.1:40000", rewrite("mongodb://localhost:32771"));
        assertEquals(List.of("localhost:32771"), seen);
        assertEquals("mongodb://127.0.0.1:40000/shop?retryWrites=true", rewrite("mongodb://db.internal/shop?retryWrites=true"));
        assertEquals(List.of("db.internal:27017"), seen);
    }

    @Test
    void credentialsAndSeveralHosts() {
        assertEquals("mongodb://u:p@127.0.0.1:40000,127.0.0.1:40001/db?replicaSet=rs0",
                rewrite("mongodb://u:p@a:27017,b:27018/db?replicaSet=rs0"));
        assertEquals(List.of("a:27017", "b:27018"), seen);
    }

    @Test
    void srvAndOtherFormsAreLeftAlone() {
        assertNull(rewrite("mongodb+srv://cluster.example.com/db"));
        assertNull(rewrite("not a connection string"));
    }
}
