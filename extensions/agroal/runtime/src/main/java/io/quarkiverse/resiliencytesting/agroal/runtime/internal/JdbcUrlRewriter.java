package io.quarkiverse.resiliencytesting.agroal.runtime.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkiverse.resiliencytesting.datasource.DatabaseUrls;
import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;

/**
 * JDBC URLs: {@code jdbc:<subprotocol>://[user@]host[:port]/...} for every driver, plus Oracle's
 * {@code jdbc:oracle:thin:@host:port/service} form. Multi-host URLs and non-network ones ({@code jdbc:h2:mem},
 * {@code jdbc:sqlite:file}) are left alone.
 */
public class JdbcUrlRewriter implements AddressRewriter {

    private static final Pattern GENERIC = Pattern.compile("^(jdbc:([a-z0-9]+):(?:[a-z]+:)?//)([^/?;,]+)(.*)$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String rewrite(String value, Context context) {
        String oracle = DatabaseUrls.rewriteOracle(value, context);
        if (oracle != null) {
            return oracle;
        }
        Matcher m = GENERIC.matcher(value);
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(4);
        if (rest.startsWith(",")) {
            return null;
        }
        AddressUtil.HostPort hp = AddressUtil.hostPort(m.group(3),
                DatabaseUrls.DEFAULT_PORTS.getOrDefault(m.group(2).toLowerCase(), -1));
        if (hp == null) {
            return null;
        }
        return m.group(1) + hp.userInfoPrefix() + AddressUtil.PROXY_HOST + ":"
                + context.proxyPort(hp.upstream()) + rest;
    }
}
