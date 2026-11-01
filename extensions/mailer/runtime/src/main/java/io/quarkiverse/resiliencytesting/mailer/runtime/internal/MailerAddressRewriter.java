package io.quarkiverse.resiliencytesting.mailer.runtime.internal;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;
import io.quarkiverse.resiliencytesting.spi.Upstream;
import io.vertx.ext.mail.MailConfig;

/**
 * The address of a mailer, split over {@code host} and {@code port}. Whichever of the two is looked up, the upstream
 * is the configured host and port, unset ones being what Vert.x defaults them to; the host becomes the proxy host
 * and the port the proxy port, so the two agree on one proxy.
 */
public class MailerAddressRewriter implements AddressRewriter {

    static final String HOST = "host";
    static final String PORT = "port";

    @Override
    public String rewrite(String value, Context context) {
        String property = context.property();
        String prefix = property.substring(0, property.lastIndexOf('.') + 1);
        boolean host = HOST.equals(property.substring(prefix.length()));
        String hostValue = host ? value : context.neighbour(prefix + HOST);
        if (hostValue == null || hostValue.isBlank()) {
            hostValue = MailConfig.DEFAULT_HOST;
        }
        String portValue = host ? context.neighbour(prefix + PORT) : value;
        int port;
        try {
            port = portValue == null || portValue.isBlank() ? MailConfig.DEFAULT_PORT : Integer.parseInt(portValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int proxyPort = context.proxyPort(Upstream.of(hostValue.trim(), port));
        return host ? AddressUtil.PROXY_HOST : Integer.toString(proxyPort);
    }
}
