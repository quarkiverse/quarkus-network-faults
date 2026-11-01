package io.quarkiverse.resiliencytesting.spi;

/**
 * A comma-separated list of {@code host[:port]} entries, each optionally prefixed with {@code scheme://}. Extend it
 * to supply the port an entry without one means, as a bootstrap list does.
 */
public class HostPortListRewriter implements AddressRewriter {

    /**
     * The port for an entry that names none; by default there is none and such an entry is not proxied.
     */
    protected int defaultPort() {
        return -1;
    }

    @Override
    public String rewrite(String value, Context context) {
        return AddressUtil.rewriteList(value, context, new AddressUtil.ElementRewriter() {
            @Override
            public String rewrite(String element, Context context) {
                return rewriteElement(element, context);
            }
        });
    }

    private String rewriteElement(String element, Context context) {
        String prefix = "";
        int scheme = element.indexOf("://");
        if (scheme > 0) {
            prefix = element.substring(0, scheme + 3);
            element = element.substring(scheme + 3);
        }
        AddressUtil.HostPort hp = AddressUtil.hostPort(element, defaultPort());
        if (hp == null) {
            return null;
        }
        return prefix + AddressUtil.PROXY_HOST + ":" + context.proxyPort(hp.upstream());
    }
}
