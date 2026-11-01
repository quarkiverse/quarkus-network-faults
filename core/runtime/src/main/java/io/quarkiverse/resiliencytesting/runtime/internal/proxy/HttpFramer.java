package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;
import io.vertx.core.buffer.Buffer;

/**
 * Follows the plaintext HTTP/1.x messages that travel one way over a connection, byte by byte and message after
 * message: the start line, the headers, then the body the way the headers frame it, by {@code Content-Length} or as
 * chunks with their trailers, and on to the next start line. That is what tells one message from the next on a
 * connection that is kept alive, and a body from the message behind it.
 * <p>
 * A stream that is not HTTP/1.x, such as TLS, HTTP/2 or a WebSocket after its upgrade, fails the walk at a start line
 * and is left alone from then on.
 */
abstract class HttpFramer {

    enum State {
        START_LINE,
        HEADERS,
        SIZED_BODY,
        CHUNK_SIZE,
        CHUNK_EXTENSION,
        CHUNK_DATA,
        CHUNK_DATA_END,
        TRAILERS,
        BODY_UNTIL_CLOSE,
        OPAQUE
    }

    /**
     * What the version in a start line begins with, and how long the version is: {@code HTTP/1.0} or
     * {@code HTTP/1.1}.
     */
    static final String VERSION_PREFIX = "HTTP/1.";
    static final int VERSION_LENGTH = VERSION_PREFIX.length() + 1;

    /**
     * How much of a start line or header is kept. Servers refuse lines well short of this (Vert.x allows a start
     * line of 4096 bytes and headers of 8192), so a longer one before any line break is not HTTP.
     */
    private static final int MAX_LINE = 8192;

    private State state;
    private final StringBuilder line = new StringBuilder();
    // what the headers of the current message say about its body
    private long contentLength;
    private boolean chunked;
    private boolean eventStream;
    // bytes left of a sized body or of the current chunk
    private long remaining;
    private boolean sizeSeen;
    // the marks in the buffer being walked
    private final List<HttpMark> marks = new ArrayList<>();

    /**
     * @param follow false for a stream known not to be HTTP, which is left alone from the start
     */
    HttpFramer(boolean follow) {
        this.state = follow ? State.START_LINE : State.OPAQUE;
    }

    /**
     * The start line of the next message; false when it is not one HTTP/1.x would have here.
     */
    abstract boolean startLine(String line);

    /**
     * One character of a start line as it arrives, at {@code index} in it; false when no start line HTTP/1.x would
     * have here begins like that, so that a stream that is not HTTP is told before its first line break.
     */
    boolean startLineChar(char c, int index) {
        return true;
    }

    /**
     * Where the walk goes after the blank line that ends the headers, {@code offset} being just past it in the
     * buffer being walked.
     */
    abstract State afterHeaders(int offset);

    /**
     * Whether {@link #bodyByte} wants the body of the current message; a body nobody looks at is skipped over.
     */
    boolean inspectsBody() {
        return false;
    }

    /**
     * One byte of the body, framing removed; {@code offset} is just past it.
     */
    void bodyByte(byte b, int offset) {
    }

    /**
     * A chunk ended with its CRLF, {@code offset} being just past it.
     */
    void chunkEnded(int offset) {
    }

    /**
     * A header of the current message, name and value as sent.
     */
    void headerSeen(String name, String value) {
    }

    /**
     * The current message ended, body and trailers included; the next byte starts another.
     */
    void messageEnded() {
    }

    boolean chunked() {
        return chunked;
    }

    boolean eventStream() {
        return eventStream;
    }

    boolean opaque() {
        return state == State.OPAQUE;
    }

    /**
     * Whether the next byte starts a message: nothing of one has arrived since the last one ended.
     */
    boolean betweenMessages() {
        return state == State.START_LINE && line.isEmpty();
    }

    /**
     * Notes a place in the buffer being walked where a fault can act.
     */
    void mark(HttpMark.Kind kind, int count, int offset) {
        marks.add(new HttpMark(kind, count, offset));
    }

    /**
     * Follows the next buffer of the stream and returns the marks found in it.
     */
    List<HttpMark> walk(Buffer data) {
        marks.clear();
        int end = data.length();
        int i = 0;
        while (i < end && state != State.OPAQUE) {
            boolean body = state == State.SIZED_BODY || state == State.CHUNK_DATA || state == State.BODY_UNTIL_CLOSE;
            if (body && !inspectsBody()) {
                long skipped = state == State.BODY_UNTIL_CLOSE ? end - i : Math.min(remaining, end - i);
                i += (int) skipped;
                bodyConsumed(skipped);
            } else if (body) {
                bodyByte(data.getByte(i), i + 1);
                i++;
                bodyConsumed(1);
            } else {
                step(data.getByte(i), i + 1);
                i++;
            }
        }
        return List.copyOf(marks);
    }

    private void bodyConsumed(long bytes) {
        if (state == State.BODY_UNTIL_CLOSE) {
            return;
        }
        remaining -= bytes;
        if (remaining == 0) {
            state = state == State.SIZED_BODY ? State.START_LINE : State.CHUNK_DATA_END;
            if (state == State.START_LINE) {
                messageEnded();
            }
        }
    }

    private void step(byte b, int offset) {
        switch (state) {
            case CHUNK_SIZE -> chunkSizeByte(b);
            case CHUNK_EXTENSION -> {
                if (b == '\n') {
                    chunkSizeDone();
                }
            }
            case CHUNK_DATA_END -> {
                if (b == '\n') {
                    state = State.CHUNK_SIZE;
                    chunkEnded(offset);
                } else if (b != '\r') {
                    state = State.OPAQUE;
                }
            }
            default -> lineByte(b, offset);
        }
    }

    /**
     * One byte of a start line, a header or a trailer.
     */
    private void lineByte(byte b, int offset) {
        if (b == '\r') {
            return;
        }
        if (b != '\n') {
            char c = (char) (b & 0xff);
            if (state == State.START_LINE && !startLineChar(c, line.length())) {
                state = State.OPAQUE;
            } else if (line.length() < MAX_LINE) {
                line.append(c);
            } else if (state == State.START_LINE) {
                state = State.OPAQUE;
            }
            return;
        }
        String text = line.toString();
        line.setLength(0);
        switch (state) {
            case START_LINE -> {
                if (text.isEmpty()) {
                    return; // a stray CRLF between two messages
                }
                if (!startLine(text)) {
                    state = State.OPAQUE;
                    return;
                }
                contentLength = -1;
                chunked = false;
                eventStream = false;
                state = State.HEADERS;
            }
            case HEADERS -> {
                if (text.isEmpty()) {
                    state = afterHeaders(offset);
                    if (state == State.START_LINE) {
                        messageEnded(); // a message without a body
                    }
                } else {
                    header(text);
                }
            }
            default -> {
                // trailers end with an empty line, and with them the message
                if (text.isEmpty()) {
                    state = State.START_LINE;
                    messageEnded();
                }
            }
        }
    }

    private void header(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            return;
        }
        String name = text.substring(0, colon).trim();
        String value = text.substring(colon + 1).trim();
        headerSeen(name, value);
        if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(name)) {
            try {
                contentLength = Long.parseLong(value);
            } catch (NumberFormatException e) {
                state = State.OPAQUE;
            }
        } else if (HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)) {
            chunked = chunked || AsciiString.containsIgnoreCase(value, HttpHeaderValues.CHUNKED);
        } else if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            // the media type may be followed by parameters, such as a charset
            AsciiString eventStreamType = HttpHeaderValues.TEXT_EVENT_STREAM;
            eventStream = AsciiString.regionMatches(value, true, 0, eventStreamType, 0, eventStreamType.length());
        }
    }

    /**
     * The state for the body the headers frame, {@code withoutLength} when they say nothing about one.
     */
    State framedBody(State withoutLength) {
        if (chunked) {
            return State.CHUNK_SIZE;
        }
        if (contentLength > 0) {
            remaining = contentLength;
            return State.SIZED_BODY;
        }
        return contentLength == 0 ? State.START_LINE : withoutLength;
    }

    private void chunkSizeByte(byte b) {
        // a chunk's size is written in hexadecimal
        int digit = Character.digit(b, 16);
        if (digit >= 0) {
            remaining = remaining * 16 + digit;
            sizeSeen = true;
        } else if (b == ';') {
            state = State.CHUNK_EXTENSION;
        } else if (b == '\n') {
            chunkSizeDone();
        } else if (b != '\r' && b != ' ' && b != '\t') {
            state = State.OPAQUE;
        }
    }

    private void chunkSizeDone() {
        if (!sizeSeen) {
            state = State.OPAQUE;
        } else if (remaining == 0) {
            state = State.TRAILERS; // the last chunk
        } else {
            state = State.CHUNK_DATA;
        }
        sizeSeen = false;
    }
}
