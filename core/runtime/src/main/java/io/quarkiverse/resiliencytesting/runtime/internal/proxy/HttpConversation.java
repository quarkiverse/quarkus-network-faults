package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.vertx.core.buffer.Buffer;

/**
 * The HTTP/1.x exchange on one connection: the requests the application sends and the responses the upstream
 * answers with, each side followed message by message, and paired, so that every buffer is known to belong to one
 * {@link HttpExchange}, the request it carries or the request its response answers. A connection whose target is not
 * HTTP has one too, which leaves both sides alone.
 * <p>
 * The exchange of a buffer is the one it starts, or the one it continues. Clients do not pipeline, so a buffer
 * carries one message; one that carries more is put down to the last request it starts, or to the response it
 * starts with.
 */
final class HttpConversation {

    /**
     * A buffer to deliver, with the marks the conversation found in it, the exchange it belongs to, null when it is
     * not HTTP, and whether it starts a message rather than continuing one.
     */
    record Followed(Buffer data, List<HttpMark> marks, HttpExchange exchange, boolean starts) {
    }

    private final ProxyConnection connection;
    private final HttpRequestFramer requests;
    private final HttpResponseFramer responses;
    // the requests whose responses have not arrived yet, oldest first
    private final ArrayDeque<HttpExchange> awaitingResponse = new ArrayDeque<>();
    // the request the application is sending, or sent last
    private HttpExchange sending;
    // the request whose response the upstream is sending, or sent last
    private HttpExchange answering;
    // the start of a request whose request line has not ended yet, so that nobody can tell which request it is
    private Buffer undecided;

    HttpConversation(ProxyConnection connection) {
        this.connection = connection;
        boolean http = connection.target().upstream().http();
        this.requests = new HttpRequestFramer(this, http);
        this.responses = new HttpResponseFramer(this, http);
    }

    /**
     * Follows the next buffer of one direction and returns it with its marks, where a request's headers end for a
     * buffer the application sent and the places in a response for one the upstream sent. Returns null for the
     * start of a request whose request line has not ended: it is kept and delivered in front of the buffer that
     * ends it.
     */
    Followed follow(Direction direction, Buffer data) {
        return direction == Direction.UPSTREAM ? followRequest(data) : followResponse(data);
    }

    private Followed followRequest(Buffer data) {
        if (requests.opaque()) {
            return new Followed(data, List.of(), null, false);
        }
        boolean starts = undecided != null || requests.betweenMessages();
        HttpExchange before = sending;
        List<HttpMark> marks = requests.walk(data);
        Buffer whole = data;
        if (undecided != null) {
            marks = shifted(marks, undecided.length());
            whole = undecided.appendBuffer(data);
            undecided = null;
        }
        if (!starts || sending != before) {
            return new Followed(whole, marks, sending, starts);
        }
        if (requests.opaque()) {
            return new Followed(whole, marks, null, false); // what looked like the start of a request was not one
        }
        undecided = whole;
        return null;
    }

    private Followed followResponse(Buffer data) {
        if (responses.opaque()) {
            return new Followed(data, List.of(), null, false);
        }
        boolean starts = responses.betweenMessages();
        if (starts) {
            answering = awaitingResponse.peek(); // the next response answers the oldest request
        }
        return new Followed(data, responses.walk(data), answering, starts);
    }

    private static List<HttpMark> shifted(List<HttpMark> marks, int by) {
        List<HttpMark> out = new ArrayList<>(marks.size());
        for (HttpMark mark : marks) {
            out.add(new HttpMark(mark.kind(), mark.count(), mark.offset() + by));
        }
        return out;
    }

    void requestSeen(String method, String target) {
        sending = new HttpExchange(method, target);
        connection.requestSeen(sending);
        if (!responses.opaque()) {
            awaitingResponse.add(sending);
        }
    }

    void requestHeaders(Map<String, List<String>> headers) {
        connection.target().stats().requestHeaders(sending, headers);
    }

    void requestBody(String fingerprint) {
        connection.target().stats().requestBody(sending, fingerprint);
    }

    /**
     * The request the final response now arriving answers, or null when no request is waiting for one; the request's
     * response time is taken now.
     */
    HttpExchange takeRequest() {
        HttpExchange answered = awaitingResponse.poll();
        if (answered != null) {
            connection.target().stats().responseSeen(answered, responses.status());
        }
        return answered;
    }

    /**
     * A fault answered the oldest request waiting for a response with {@code status} in place of the upstream; the
     * request's response time is taken now.
     */
    void answeredByFault(int status) {
        HttpExchange answered = awaitingResponse.poll();
        if (answered != null) {
            connection.target().stats().responseSeen(answered, status);
        }
    }
}
