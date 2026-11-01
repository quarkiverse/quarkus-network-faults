package io.quarkiverse.resiliencytesting.grpc.runtime.internal;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;
import io.quarkiverse.resiliencytesting.spi.Upstream;

/**
 * The address of a gRPC client, split over {@code host}, {@code port} and {@code test-port}. Whichever of the
 * three is looked up, the upstream is the configured host with the port the client will use: {@code test-port}
 * when set (Quarkus ignores {@code port} in tests otherwise, and points the client at the application's own HTTP
 * port, which cannot be proxied), else {@code port}, else the gRPC default. The host becomes the proxy host and
 * both port properties the proxy port, so the three agree on one proxy.
 */
public class GrpcClientAddressRewriter implements AddressRewriter {

    static final String HOST = "host";
    static final String PORT = "port";
    static final String TEST_PORT = "test-port";
    // what quarkus.grpc.clients.<name>.host and .port default to
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    @Override
    public String rewrite(String value, Context context) {
        String property = context.property();
        String prefix = property.substring(0, property.lastIndexOf('.') + 1);
        String attribute = property.substring(prefix.length());
        String host = HOST.equals(attribute) ? value : context.neighbour(prefix + HOST);
        if (host == null || host.isBlank()) {
            host = DEFAULT_HOST;
        }
        String portValue = TEST_PORT.equals(attribute) ? value : context.neighbour(prefix + TEST_PORT);
        if (portValue == null || portValue.isBlank()) {
            portValue = PORT.equals(attribute) ? value : context.neighbour(prefix + PORT);
        }
        int port;
        try {
            port = portValue == null || portValue.isBlank() ? DEFAULT_PORT : Integer.parseInt(portValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int proxyPort = context.proxyPort(Upstream.of(host, port));
        return HOST.equals(attribute) ? AddressUtil.PROXY_HOST : Integer.toString(proxyPort);
    }
}
