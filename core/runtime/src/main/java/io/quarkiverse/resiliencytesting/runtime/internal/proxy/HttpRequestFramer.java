package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows the requests the application sends on one connection and reports each one as its request line arrives, so
 * requests are counted even when a fault never relays them, and marks where the headers of each end. It records the
 * headers, and a fingerprint of the body, for the request records a test reads back: whether every retry carried the
 * same idempotency key and the same body is what those are for.
 */
final class HttpRequestFramer extends HttpFramer {

    private final HttpConversation conversation;
    // whether the method, the first word of the request line arriving, has ended
    private boolean methodEnded;
    // the headers of the request arriving, in order
    private Map<String, List<String>> headers = new LinkedHashMap<>();
    // the body of the request arriving, hashed as it is stepped over; null while none has
    private MessageDigest body;

    HttpRequestFramer(HttpConversation conversation, boolean follow) {
        super(follow);
        this.conversation = conversation;
    }

    /**
     * {@code METHOD target HTTP/1.x}
     */
    @Override
    boolean startLine(String line) {
        int afterMethod = line.indexOf(' ');
        int beforeVersion = line.lastIndexOf(' ');
        if (afterMethod <= 0 || beforeVersion <= afterMethod + 1 || line.length() != beforeVersion + 1 + VERSION_LENGTH
                || !line.startsWith(VERSION_PREFIX, beforeVersion + 1)) {
            return false;
        }
        headers = new LinkedHashMap<>();
        body = null;
        conversation.requestSeen(line.substring(0, afterMethod), line.substring(afterMethod + 1, beforeVersion));
        return true;
    }

    /**
     * The method is upper case letters and dashes, up to the space that ends it.
     */
    @Override
    boolean startLineChar(char c, int index) {
        if (index == 0) {
            methodEnded = false;
        }
        if (methodEnded) {
            return true;
        }
        if (c == ' ' && index > 0) {
            methodEnded = true;
            return true;
        }
        return c >= 'A' && c <= 'Z' || c == '-';
    }

    @Override
    void headerSeen(String name, String value) {
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>();
            headers.put(name, values);
        }
        values.add(value);
    }

    @Override
    State afterHeaders(int offset) {
        mark(HttpMark.Kind.REQUEST_HEADERS, 0, offset);
        conversation.requestHeaders(headers);
        return framedBody(State.START_LINE);
    }

    @Override
    boolean inspectsBody() {
        return true;
    }

    @Override
    void bodyByte(byte b, int offset) {
        if (body == null) {
            body = sha256();
        }
        body.update(b);
    }

    @Override
    void messageEnded() {
        if (body != null) {
            conversation.requestBody(HexFormat.of().formatHex(body.digest()));
            body = null;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has it
        }
    }
}
