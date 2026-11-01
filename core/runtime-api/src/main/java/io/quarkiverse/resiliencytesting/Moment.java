package io.quarkiverse.resiliencytesting;

/**
 * The moment in a request at which a mid-request fault strikes: {@link Endpoint#diesMidRequest(Moment)} resets the
 * connection there, {@link Endpoint#hangs(Moment)} holds it there. {@link Endpoint#diesMidRequest()} uses
 * {@link #afterBytes(long) afterBytes(0)}: the reset comes as soon as the upstream starts to respond.
 * <p>
 * On an endpoint that covers some requests only, the client's writes and the response bytes are those of the requests
 * it covers.
 * <p>
 * The moments here work on any protocol. Those under {@link Http} read the response and need plaintext HTTP/1.x.
 */
public sealed interface Moment {

    /**
     * Relay the given number of response bytes, then strike.
     */
    static Moment afterBytes(long bytes) {
        return new AfterBytes(bytes);
    }

    /**
     * Strike when the client next sends data, so the request never reaches the upstream.
     */
    static Moment onNextWrite() {
        return new OnNextWrite();
    }

    record AfterBytes(long bytes) implements Moment {
        public AfterBytes {
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes must be >= 0");
            }
        }
    }

    record OnNextWrite() implements Moment {
    }

    /**
     * Moments in a plaintext HTTP/1.x response, for a streamed response cut or stalled half-way. Chunks and events
     * are counted from the start of their response, so on a connection that is kept alive the fault strikes in the
     * first response that gets that far. They can only be applied to a target reached over plaintext HTTP, an
     * {@code http} or {@code ws} address: applying one to any other target, such as a datasource or a client with an
     * {@code https} URL, throws {@link UnsupportedOperationException}. On such a target that turns out to speak
     * HTTP/2 they are never reached and the connection is left alone.
     */
    interface Http {

        /**
         * Relay the status line and the response headers, then strike before any of the body. An interim response,
         * such as {@code 100 Continue}, is relayed and the response after it is the one cut.
         */
        static Moment afterHeaders() {
            return new AfterHeaders();
        }

        /**
         * Relay the response headers and the given number of HTTP/1.1 chunked transfer encoding chunks, then strike
         * on that chunk boundary. Zero strikes right after the headers of a chunked response. A response that is
         * not chunked is relayed whole.
         */
        static Moment afterChunks(int chunks) {
            return new AfterChunks(chunks);
        }

        /**
         * Relay the response headers and the given number of Server-Sent Events, then strike right after the blank
         * line that ends the last one. Events are counted in the body of a {@code text/event-stream} response,
         * whether or not it is chunked; zero strikes right after its headers. Any other response is relayed whole.
         */
        static Moment afterSseEvents(int events) {
            return new AfterSseEvents(events);
        }

        record AfterHeaders() implements Moment {
        }

        record AfterChunks(int chunks) implements Moment {
            public AfterChunks {
                if (chunks < 0) {
                    throw new IllegalArgumentException("chunks must be >= 0");
                }
            }
        }

        record AfterSseEvents(int events) implements Moment {
            public AfterSseEvents {
                if (events < 0) {
                    throw new IllegalArgumentException("events must be >= 0");
                }
            }
        }
    }
}
