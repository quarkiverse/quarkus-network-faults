package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;

/**
 * Follows the responses the upstream sends on one connection and marks, in each buffer, the places where a
 * mid-request fault can strike: the end of a response's headers, of each of its chunks, and of each Server-Sent
 * Event in the body of an event stream. Chunks and events are counted from the start of their response.
 */
final class HttpResponseFramer extends HttpFramer {

    private final HttpConversation conversation;
    private int status;
    private int chunks;
    // Server-Sent Events, over the body bytes
    private int lineLength;
    private boolean eventHasContent;
    private int events;

    HttpResponseFramer(HttpConversation conversation, boolean follow) {
        super(follow);
        this.conversation = conversation;
    }

    int status() {
        return status;
    }

    /**
     * {@code HTTP/1.x status reason}
     */
    @Override
    boolean startLine(String line) {
        // the version, a space, three digits of status, then a space and the reason or nothing
        int statusStart = VERSION_LENGTH + 1;
        int statusEnd = statusStart + 3;
        if (!line.startsWith(VERSION_PREFIX) || line.length() < statusEnd || line.charAt(VERSION_LENGTH) != ' '
                || line.length() > statusEnd && line.charAt(statusEnd) != ' ') {
            return false;
        }
        try {
            status = Integer.parseInt(line.substring(statusStart, statusEnd));
        } catch (NumberFormatException e) {
            return false;
        }
        chunks = 0;
        events = 0;
        lineLength = 0;
        eventHasContent = false;
        return true;
    }

    @Override
    State afterHeaders(int offset) {
        if (status == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            return State.OPAQUE; // the connection now speaks another protocol
        }
        if (HttpStatusClass.INFORMATIONAL.contains(status)) {
            return State.START_LINE; // interim: the response itself follows
        }
        HttpExchange answered = conversation.takeRequest();
        boolean toHead = answered != null && HttpMethod.HEAD.name().equals(answered.method());
        mark(HttpMark.Kind.RESPONSE_HEADERS, 0, offset);
        if (toHead || status == HttpResponseStatus.NO_CONTENT.code()
                || status == HttpResponseStatus.NOT_MODIFIED.code()) {
            return State.START_LINE; // these never have a body, whatever their headers announce
        }
        if (chunked()) {
            mark(HttpMark.Kind.CHUNK, 0, offset);
        }
        if (eventStream()) {
            mark(HttpMark.Kind.SSE_EVENT, 0, offset);
        }
        return framedBody(State.BODY_UNTIL_CLOSE);
    }

    @Override
    void chunkEnded(int offset) {
        chunks++;
        mark(HttpMark.Kind.CHUNK, chunks, offset);
    }

    @Override
    boolean inspectsBody() {
        return eventStream();
    }

    @Override
    void bodyByte(byte b, int offset) {
        if (b == '\r') {
            return;
        }
        if (b != '\n') {
            lineLength++;
            return;
        }
        if (lineLength > 0) {
            lineLength = 0;
            eventHasContent = true;
            return;
        }
        // a blank line dispatches the event, if there was one
        if (!eventHasContent) {
            return;
        }
        eventHasContent = false;
        events++;
        mark(HttpMark.Kind.SSE_EVENT, events, offset);
    }
}
