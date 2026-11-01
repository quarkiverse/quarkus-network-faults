package io.quarkiverse.resiliencytesting.mongodb.runtime.internal;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;

/**
 * A {@code mongodb://[user:password@]host1[:port1][,host2[:port2]...][/database][?options]} connection string: the
 * hosts sit together in one authority, which is a {@code hosts} list as {@link MongoHostsRewriter} rewrites it. A
 * {@code mongodb+srv://} string names a DNS record, not a server, and cannot be proxied.
 */
public class MongoConnectionStringRewriter implements AddressRewriter {

    private static final String SCHEME = "mongodb://";
    private static final MongoHostsRewriter HOSTS = new MongoHostsRewriter();

    @Override
    public String rewrite(String value, Context context) {
        if (!value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String rest = value.substring(SCHEME.length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        String tail = rest.substring(end);
        String userInfo = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at + 1);
            authority = authority.substring(at + 1);
        }
        String hosts = HOSTS.rewrite(authority, context);
        return hosts == null ? null : value.substring(0, SCHEME.length()) + userInfo + hosts + tail;
    }
}
