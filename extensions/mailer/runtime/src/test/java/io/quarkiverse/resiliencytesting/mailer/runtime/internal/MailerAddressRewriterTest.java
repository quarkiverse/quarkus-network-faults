package io.quarkiverse.resiliencytesting.mailer.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class MailerAddressRewriterTest {

    private final List<String> seen = new ArrayList<>();
    private final Map<String, String> config = new HashMap<>();
    private final MailerAddressRewriter rewriter = new MailerAddressRewriter();

    private String rewrite(String property) {
        seen.clear();
        return rewriter.rewrite(config.get(property), new AddressRewriter.Context() {
            @Override
            public String property() {
                return property;
            }

            @Override
            public int proxyPort(Upstream upstream) {
                seen.add(upstream.host() + ":" + upstream.port());
                return 40000;
            }

            @Override
            public String neighbour(String other) {
                return config.get(other);
            }
        });
    }

    @Test
    public void hostAndPortAgreeOnOneProxy() {
        config.put("quarkus.mailer.host", "smtp.example.com");
        config.put("quarkus.mailer.port", "587");
        assertEquals("127.0.0.1", rewrite("quarkus.mailer.host"));
        assertEquals(List.of("smtp.example.com:587"), seen);
        assertEquals("40000", rewrite("quarkus.mailer.port"));
        assertEquals(List.of("smtp.example.com:587"), seen);
    }

    @Test
    public void aNamedMailerReadsItsOwnNeighbour() {
        config.put("quarkus.mailer.\"alerts.eu\".host", "relay");
        config.put("quarkus.mailer.\"alerts.eu\".port", "2525");
        config.put("quarkus.mailer.port", "25");
        assertEquals("127.0.0.1", rewrite("quarkus.mailer.\"alerts.eu\".host"));
        assertEquals(List.of("relay:2525"), seen);
    }

    @Test
    public void anUnsetHostOrPortIsWhatVertxDefaultsTo() {
        config.put("quarkus.mailer.host", "relay");
        assertEquals("127.0.0.1", rewrite("quarkus.mailer.host"));
        assertEquals(List.of("relay:25"), seen);
        config.clear();
        config.put("quarkus.mailer.port", "2525");
        assertEquals("40000", rewrite("quarkus.mailer.port"));
        assertEquals(List.of("localhost:2525"), seen);
    }

    @Test
    public void aPortThatIsNotANumberIsLeftAlone() {
        config.put("quarkus.mailer.port", "smtp");
        assertNull(rewrite("quarkus.mailer.port"));
    }
}
