package io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal;

import io.quarkiverse.resiliencytesting.datasource.DatabaseUrls;
import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;

/**
 * Reactive client URLs: a comma-separated list of {@code [vertx-reactive:]<db>://[user@]host[:port]/...}, plus
 * Oracle's {@code oracle:thin:@host:port/service} form.
 */
public class ReactiveUrlRewriter implements AddressRewriter {

    private static final String PREFIX = "vertx-reactive:";

    @Override
    public String rewrite(String value, Context context) {
        return AddressUtil.rewriteList(value, context, new AddressUtil.ElementRewriter() {
            @Override
            public String rewrite(String element, Context context) {
                return rewriteElement(element, context);
            }
        });
    }

    private static String rewriteElement(String url, Context context) {
        String prefix = "";
        if (url.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            prefix = url.substring(0, PREFIX.length());
            url = url.substring(prefix.length());
        }
        String rewritten = DatabaseUrls.rewriteOracle(url, context);
        if (rewritten == null) {
            rewritten = AddressUtil.rewriteUrl(url, context, DatabaseUrls.DEFAULT_PORTS);
        }
        return rewritten == null ? null : prefix + rewritten;
    }
}
