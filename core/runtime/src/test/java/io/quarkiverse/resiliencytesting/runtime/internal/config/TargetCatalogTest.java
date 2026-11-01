package io.quarkiverse.resiliencytesting.runtime.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.HostPortListRewriter;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;
import io.quarkiverse.resiliencytesting.spi.UrlRewriter;

class TargetCatalogTest {

    /**
     * A bootstrap-style list with a default port, as a technology module would declare it.
     */
    public static class BrokerListRewriter extends HostPortListRewriter {
        @Override
        protected int defaultPort() {
            return 9092;
        }
    }

    /**
     * The shapes the technology modules declare: literal properties, and templates where no build item exists.
     */
    private static final TargetCatalog CATALOG = TargetCatalog.of(List.of(
            new TargetDescriptor("quarkus.datasource.jdbc.url", UrlRewriter.class,
                    TargetName.of("datasource", "<default>", "jdbc")),
            new TargetDescriptor("quarkus.datasource.users.jdbc.url", UrlRewriter.class,
                    TargetName.of("datasource", "users", "jdbc")),
            new TargetDescriptor("quarkus.datasource.\"my.ds\".jdbc.url", UrlRewriter.class,
                    TargetName.of("datasource", "my.ds", "jdbc")),
            new TargetDescriptor("quarkus.datasource.reactive.url", UrlRewriter.class,
                    TargetName.of("datasource", "<default>", "reactive")),
            new TargetDescriptor("acme.{name}.url", UrlRewriter.class, TargetName.of("acme", "{name}")),
            new TargetDescriptor("kafka.bootstrap.servers", BrokerListRewriter.class,
                    TargetName.of("kafka", "{host}", "{port}")),
            new TargetDescriptor("mp.messaging.outgoing.{channel}.bootstrap.servers", BrokerListRewriter.class,
                    TargetName.of("kafka", "{host}", "{port}")),
            new TargetDescriptor("quarkus.langchain4j.openai.base-url", UrlRewriter.class,
                    TargetName.of("langchain4j", "openai")),
            new TargetDescriptor("quarkus.langchain4j.ollama.\"my-model\".base-url", UrlRewriter.class,
                    TargetName.of("langchain4j", "ollama", "my-model"))));

    private final List<String> seen = new ArrayList<>();
    private Upstream lastUpstream;
    private final AddressRewriter.Context context = new AddressRewriter.Context() {
        @Override
        public String property() {
            return "p";
        }

        @Override
        public int proxyPort(Upstream upstream) {
            seen.add(upstream.host() + ":" + upstream.port());
            lastUpstream = upstream;
            return 40000 + seen.size() - 1;
        }

        @Override
        public String neighbour(String property) {
            return null;
        }
    };

    private String rewrite(AddressRewriter rewriter, String value) {
        seen.clear();
        return rewriter.rewrite(value, context);
    }

    /**
     * The match for a property that must be recognised.
     */
    private static TargetCatalog.Match matchOf(String property) {
        TargetCatalog.Match match = CATALOG.match(property);
        assertNotNull(match, property + " should be recognised");
        return match;
    }

    private static String nameOf(String property) {
        return matchOf(property).targetName().toString();
    }

    @Test
    void httpUrls() {
        UrlRewriter urls = new UrlRewriter();
        assertEquals("https://127.0.0.1:40000/api/v1?k=v", rewrite(urls, "https://inventory.svc:8443/api/v1?k=v"));
        assertEquals(List.of("inventory.svc:8443"), seen);
        assertEquals("http://127.0.0.1:40000", rewrite(urls, "http://inventory"));
        assertEquals(List.of("inventory:80"), seen);
        assertNull(rewrite(urls, "/relative"));
        assertNull(rewrite(urls, "not a url"));
        assertNull(rewrite(urls, "redis://cache"));
    }

    @Test
    void urlRewritingPreservesEncodedComponents() {
        UrlRewriter urls = new UrlRewriter();
        String suffix = "/files/a%2Fb/%252F/%E2%82%AC?token=x%26y%3Dz&space=a+b#part%23two";
        assertEquals("https://user%3Aname:pass%40word@127.0.0.1:40000" + suffix,
                rewrite(urls, "https://user%3Aname:pass%40word@inventory:8443" + suffix));
        assertEquals(List.of("inventory:8443"), seen);
        assertEquals("http://127.0.0.1:40000?#", rewrite(urls, "http://inventory?#"));
        assertEquals("http://127.0.0.1:40000/a%2fb", rewrite(urls, "http://[::1]:8080/a%2fb"));
        assertEquals(List.of("[::1]:8080"), seen);
    }

    @Test
    void hostPortLists() {
        BrokerListRewriter brokers = new BrokerListRewriter();
        assertEquals("127.0.0.1:40000,127.0.0.1:40001", rewrite(brokers, "kafka-1:9092, kafka-2:9093"));
        assertEquals(List.of("kafka-1:9092", "kafka-2:9093"), seen);
        assertEquals("PLAINTEXT://127.0.0.1:40000", rewrite(brokers, "PLAINTEXT://broker:9092"));
        assertEquals("127.0.0.1:40000", rewrite(brokers, "broker"));
        assertEquals(List.of("broker:9092"), seen);
        assertNull(rewrite(new HostPortListRewriter(), "broker"));
    }

    @Test
    void matchesCarryTheDescriptorsRewriter() {
        assertEquals(BrokerListRewriter.class, matchOf("kafka.bootstrap.servers").rewriter().getClass());
        assertEquals(UrlRewriter.class, matchOf("acme.billing.url").rewriter().getClass());
    }

    @Test
    void propertyMatching() {
        assertEquals("datasource:<default>:jdbc", nameOf("quarkus.datasource.jdbc.url"));
        assertEquals("datasource:users:jdbc", nameOf("quarkus.datasource.users.jdbc.url"));
        // SmallRye Config may spell a map key with quotes even when the application did not
        assertEquals("datasource:users:jdbc", nameOf("quarkus.datasource.\"users\".jdbc.url"));
        assertEquals("datasource:my.ds:jdbc", nameOf("quarkus.datasource.\"my.ds\".jdbc.url"));
        assertEquals("datasource:<default>:reactive", nameOf("quarkus.datasource.reactive.url"));
        assertEquals("datasource:<default>:reactive",
                nameOf("quarkus.datasource.reactive.url[1]"));
        assertEquals("acme:billing", nameOf("acme.billing.url"));
        assertEquals("acme:com.acme.Billing", nameOf("acme.\"com.acme.Billing\".url"));
        assertEquals("kafka:{host}:{port}", nameOf("kafka.bootstrap.servers"));
        assertEquals("kafka:{host}:{port}",
                nameOf("mp.messaging.outgoing.orders.bootstrap.servers"));
        assertEquals("langchain4j:openai", nameOf("quarkus.langchain4j.openai.base-url"));
        assertEquals("langchain4j:ollama:my-model",
                nameOf("quarkus.langchain4j.ollama.\"my-model\".base-url"));
        assertNull(CATALOG.match("quarkus.langchain4j.openai.api-key"));
        assertNull(CATALOG.match("quarkus.langchain4j.openai.summarizer.base-url"));
        assertNull(CATALOG.match("quarkus.datasource.db-kind"));
        assertNull(CATALOG.match("acme.billing.connect-timeout"));
        assertNull(CATALOG.match("quarkus.datasource.jdbc.max-size"));
    }

    @Test
    void onlyAPlaintextUrlIsAnUpstreamTheProxyCanReadAsHttp() {
        UrlRewriter rewriter = new UrlRewriter();
        rewrite(rewriter, "http://inventory:8080/api");
        assertTrue(lastUpstream.http());
        rewrite(rewriter, "ws://events:8080/feed");
        assertTrue(lastUpstream.http());
        // TLS reaches the proxy encrypted
        rewrite(rewriter, "https://inventory/api");
        assertFalse(lastUpstream.http());
        rewrite(rewriter, "wss://events/feed");
        assertFalse(lastUpstream.http());
    }
}
