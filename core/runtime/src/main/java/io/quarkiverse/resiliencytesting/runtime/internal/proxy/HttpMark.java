package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

/**
 * A place in an HTTP message where a fault can act, found in one buffer of a connection as its
 * {@link HttpConversation} followed that buffer. A fault is handed the marks of a buffer together with the buffer.
 *
 * @param kind what ends there
 * @param count how many chunks or events their response has had by then; zero for the end of headers, and for the
 *        place right after the headers of a chunked response or an event stream
 * @param offset the offset in the buffer just past the place
 */
public record HttpMark(Kind kind, int count, int offset) {

    public enum Kind {
        /**
         * The headers of a request.
         */
        REQUEST_HEADERS,
        /**
         * The headers of a response; interim (1xx) responses are not marked.
         */
        RESPONSE_HEADERS,
        /**
         * A chunk of a chunked response.
         */
        CHUNK,
        /**
         * A Server-Sent Event of a {@code text/event-stream} response.
         */
        SSE_EVENT
    }
}
