package io.quarkiverse.resiliencytesting;

import java.util.List;

/**
 * The HTTP requests an {@link Endpoint} covers that went through the proxy, for assertions on how the application
 * behaved: every request for a {@link Target}, the requests one method sends for {@code target(InventoryClient::reserve)}.
 * <p>
 * On a plaintext HTTP/1.x target the proxy follows the requests the application sends, message by message, so each
 * one is counted once, at its request line, and a body is never mistaken for a request. They are counted whether or
 * not they were relayed, so retries and calls a breaker swallowed are visible. TLS, HTTP/2 and non-HTTP protocols
 * are opaque: for those no request is seen, and only the connection counts of {@link TargetTraffic} are meaningful.
 */
public interface Traffic {

    /**
     * Plaintext HTTP/1.x requests sent through the proxy since the application started.
     */
    long httpRequestsSeen();

    /**
     * The most recent plaintext HTTP/1.x requests of the target, oldest first; only the last few hundred are kept, the
     * same number as events in its log.
     */
    List<HttpRequestRecord> httpRequests();
}
