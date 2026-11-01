package io.quarkiverse.resiliencytesting;

/**
 * Faults that act on the HTTP exchange rather than on the connection, reached through {@link Endpoint#http()}. They
 * read or synthesise a plaintext HTTP/1.x message, so they apply only to HTTP targets over plaintext HTTP/1.x; a TLS
 * or HTTP/2 target is relayed opaquely.
 */
public interface Http {

    /**
     * Answer every request the endpoint covers with a synthetic HTTP response of {@code status} instead of relaying
     * it to the real upstream, so the application sees the remote API fail the way it would for that status. A body,
     * a content type and extra headers can be added to the returned {@link HttpResponse}, and
     * {@link #forFirst(int)} on the endpoint limits it to the first few requests so the dependency appears to recover.
     *
     * @throws UnsupportedOperationException if the target is not reached over plaintext HTTP: a datasource, or a
     *         client configured with an {@code https} URL
     */
    HttpResponse respondsWith(int status);

    /**
     * The first {@code n} requests the endpoint covers, counted across its connections in the order they arrive. A
     * fault on the returned endpoint acts on those and relays the rest, so the dependency appears to recover:
     * {@code target.http().forFirst(2).http().respondsWith(503)} fails two calls and lets a {@code @Retry} succeed
     * on the third.
     *
     * @throws IllegalStateException if the endpoint already counts requests with {@link #forFirst}, {@link #every}
     *         or {@link #after}
     */
    Endpoint forFirst(int n);

    /**
     * Every {@code n}th request the endpoint covers, the first one included: one call in {@code n} fails, or is slow,
     * for a test of a retry budget, a breaker's failure ratio, or tail latency such as
     * {@code target.http().every(10).slow(Duration.ofSeconds(2))}.
     *
     * @throws IllegalStateException if the endpoint already counts requests
     */
    Endpoint every(int n);

    /**
     * Every request after the first {@code n} the endpoint covers: the dependency fails once the test is under way,
     * for a breaker that trips mid-flow or a connection reused after a success.
     *
     * @throws IllegalStateException if the endpoint already counts requests
     */
    Endpoint after(int n);

    /**
     * The requests with this method whose path ends with this one, so that a base path in the client's URL does not
     * have to be repeated: {@code requests("POST", "/v1/embeddings")}. A segment may be a template parameter as
     * {@code @Path} writes them, {@code {id}} for any one segment or {@code {id: [0-9]+}} for one that matches a
     * regular expression. The query string is not compared. Faults applied to the returned endpoint act on these
     * requests only; on an endpoint that already covers some requests, the ones that are both.
     */
    Endpoint requests(String method, String path);
}
