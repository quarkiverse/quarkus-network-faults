package io.quarkiverse.resiliencytesting.redis.runtime.internal;

import java.util.Map;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;

/**
 * The {@code hosts} list of a Redis client: {@code redis://[user:password@]host[:port][/db][?...]} URIs, also
 * {@code rediss}, {@code valkey} and {@code valkeys}, comma-separated. A {@code unix://} socket cannot be proxied.
 */
public class RedisHostsRewriter implements AddressRewriter {

    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("redis", 6379, "rediss", 6379, "valkey", 6379,
            "valkeys", 6379);

    @Override
    public String rewrite(String value, Context context) {
        return AddressUtil.rewriteList(value, context, new AddressUtil.ElementRewriter() {
            @Override
            public String rewrite(String element, Context context) {
                return AddressUtil.rewriteUrl(element, context, DEFAULT_PORTS);
            }
        });
    }
}
