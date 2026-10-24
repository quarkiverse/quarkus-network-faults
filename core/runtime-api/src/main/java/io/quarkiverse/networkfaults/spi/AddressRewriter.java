package io.quarkiverse.networkfaults.spi;

/**
 * Turns a configuration value that carries one or more upstream addresses into the same value pointing at proxies.
 * The extension that declares a {@link TargetDescriptor} names the rewriter for its property, so the shape of the
 * value (a JDBC URL, a bootstrap list, a URL) and its defaults stay with that extension. Implementations need a
 * public no-argument constructor. {@link UrlRewriter} and {@link HostPortListRewriter} cover the common shapes;
 * {@link AddressUtil} has the parsing pieces a custom one needs.
 */
public interface AddressRewriter {

    /**
     * What a rewriter has at hand while rewriting one value.
     */
    interface Context {

        /**
         * The property whose value is being rewritten.
         */
        String property();

        /**
         * The port of the proxy standing in for one upstream address. When a reserved port exists for this
         * property, the proxy binds to it; otherwise an ephemeral port is allocated.
         */
        int proxyPort(Upstream upstream);

        /**
         * The resolved value of another property, or {@code null} when it is not set: for an address split across
         * several properties, such as a gRPC client's {@code host} and {@code port}.
         */
        String neighbour(String property);
    }

    /**
     * The value with every upstream address replaced by a proxy address, or {@code null} when the value is not
     * something that can be proxied (an in-memory database, a relative URL, a form the rewriter does not know),
     * in which case it is left untouched.
     */
    String rewrite(String value, Context context);
}
