package io.quarkiverse.resiliencytesting.runtime.internal;

import java.time.Instant;

/**
 * Immutable view of one live connection through a proxy.
 */
public record ConnectionSnapshot(
        long id,
        String clientAddress,
        Instant openedAt,
        Instant lastActivity,
        long bytesUpstream,
        long bytesDownstream,
        boolean held) {
}
