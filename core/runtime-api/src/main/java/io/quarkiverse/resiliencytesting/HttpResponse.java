package io.quarkiverse.resiliencytesting;

/**
 * An active {@code respondsWith} fault: while it is in place, every request the endpoint covers is answered with a
 * synthetic HTTP response of a fixed status instead of being relayed to the real upstream, and its connection is
 * closed cleanly afterwards with {@code Connection: close}, so a pooled client opens a fresh one for its next call.
 * The status is fixed when the fault is applied; a body, a content type and extra headers can be added before the
 * first request arrives. Which requests are answered is the endpoint's business: {@link Http#forFirst},
 * {@link Http#every} and {@link Http#after} narrow it before the fault is applied.
 * <p>
 * Plaintext HTTP/1.x only, like the other HTTP-aware faults: a TLS target is relayed opaquely and cannot be
 * answered this way.
 */
public interface HttpResponse extends ActiveFault {

    /**
     * The response body, sent as UTF-8. A {@code Content-Length} is set from it unless one was given with
     * {@link #header}.
     */
    HttpResponse body(String body);

    /**
     * A body of this many bytes of filler, streamed to the client rather than held in memory, for a test of what the
     * application does with a response far larger than it expects. {@code Content-Length} is set to the length. Not
     * to be combined with {@link #body}.
     */
    HttpResponse bodyOfLength(long bytes);

    /**
     * The {@code Content-Type} of the response.
     */
    HttpResponse contentType(String contentType);

    /**
     * An extra response header; may be called more than once. A header set here wins over the ones the fault would
     * add on its own ({@code Content-Length}, {@code Content-Type}, {@code Connection}).
     */
    HttpResponse header(String name, String value);
}
