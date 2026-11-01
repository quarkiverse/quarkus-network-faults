package io.quarkiverse.resiliencytesting.runtime.internal;

import java.util.List;

/**
 * Immutable view of one proxy's state.
 */
public record TargetSnapshot(
        String name,
        String upstreamHost,
        int upstreamPort,
        int proxyPort,
        String sourceProperty,
        boolean plaintextHttp,
        boolean listening,
        String fault,
        String previousFault,
        long connectionsSeen,
        long httpRequestsSeen,
        long connectionsReset,
        long upstreamConnectFailures,
        long bytesUpstream,
        long bytesDownstream,
        List<ConnectionSnapshot> connections) {

    /**
     * The upstream as {@code host:port}.
     */
    public String upstream() {
        return upstreamHost + ":" + upstreamPort;
    }
}
