package io.quarkiverse.resiliencytesting.spi;

import java.util.Map;

/**
 * A single {@code http(s)://host[:port]/path} URL; {@code ws} and {@code wss} too. A plaintext one is an HTTP
 * upstream, whose requests are counted.
 */
public class UrlRewriter implements AddressRewriter {

    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443, "ws", 80, "wss", 443);

    @Override
    public String rewrite(String value, Context context) {
        return AddressUtil.rewriteUrl(value, context, DEFAULT_PORTS);
    }
}
