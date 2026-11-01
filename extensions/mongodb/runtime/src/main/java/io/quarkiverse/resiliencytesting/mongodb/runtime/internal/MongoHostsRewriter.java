package io.quarkiverse.resiliencytesting.mongodb.runtime.internal;

import io.quarkiverse.resiliencytesting.spi.HostPortListRewriter;

/**
 * The {@code hosts} list of a MongoDB client, {@code host[:port],...}, port 27017 when absent.
 */
public class MongoHostsRewriter extends HostPortListRewriter {

    // what a MongoDB server listens on unless told otherwise
    private static final int DEFAULT_PORT = 27017;

    @Override
    protected int defaultPort() {
        return DEFAULT_PORT;
    }
}
