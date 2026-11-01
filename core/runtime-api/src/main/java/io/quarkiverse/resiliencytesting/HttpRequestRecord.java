package io.quarkiverse.resiliencytesting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One HTTP request the application sent through a proxy, and what became of it: when it was sent, on which connection,
 * its request line, its headers, a fingerprint of its body, and how long the response took. Retries show up as several
 * records, so a test can check that every attempt carried the same idempotency key and the same body, and how far apart
 * they were. See {@link Traffic#httpRequests()} for what is visible.
 *
 * @param time when the request line arrived
 * @param connectionId the connection it was sent on
 * @param method the method of the request line
 * @param target the request target of the request line, path and query string: {@code /a?x=1}
 * @param headers the request headers in the order they were sent, a header that is repeated keeping every value
 * @param bodyFingerprint the SHA-256 of the body as a hex string, or null for a request without a body or one whose
 *        body the proxy has not read to the end
 * @param responseTime from the request line to the status line of the response, or null when the request was never
 *        answered or is still in flight; a response {@code respondsWith} answered with counts
 * @param responseStatus the status of the response, the one {@code respondsWith} answered with included, or null when
 *        the request was never answered or is still in flight
 */
public record HttpRequestRecord(Instant time, long connectionId, String method, String target,
        Map<String, List<String>> headers, String bodyFingerprint, Duration responseTime,
        Integer responseStatus) {

    /**
     * The first value of the header with this name, compared ignoring case, or null when the request has none.
     */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().getFirst();
            }
        }
        return null;
    }
}
