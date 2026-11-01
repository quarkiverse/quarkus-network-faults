package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Direction;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyConnection;
import io.vertx.core.buffer.Buffer;

/**
 * Answers every request it is handed with a synthetic HTTP/1.1 response of a fixed status instead of relaying it to
 * the real upstream, so the application sees the remote API fail the way it would for that status. The request is
 * held back until a buffer carries the mark that ends its headers, then the response is written to the client and
 * the connection is closed cleanly with {@code Connection: close}, so a pooled client opens a fresh connection for
 * its next call and that one is answered too. Nothing is ever sent to the upstream. Which requests the fault is
 * handed is the endpoint's choice: a {@link io.quarkiverse.resiliencytesting.runtime.internal.proxy.RequestScope} that
 * selects some of them relays the others without this fault seeing them.
 * <p>
 * Plaintext HTTP/1.x only: a response cannot be synthesised for a TLS target, whose bytes are relayed opaquely.
 */
public final class HttpResponseFault implements Fault {

    /**
     * How much of a request is held back while its headers have not ended. No server accepts headers this long, so
     * past it the request is answered as it is rather than buffered without bound.
     */
    private static final int MAX_REQUEST_HEADER = 64 * 1024;

    /**
     * How much of a streamed body is written at a time; the next piece follows once the socket has taken this one.
     */
    private static final int BODY_CHUNK = 64 * 1024;
    private static final byte[] FILLER = new byte[BODY_CHUNK];

    static {
        Arrays.fill(FILLER, (byte) 'x');
    }

    private final int status;
    private String body;
    // a streamed body of this many bytes instead of {@link #body}, or -1
    private long bodyLength = -1;
    private String contentType;
    private final HttpHeaders headers = new DefaultHttpHeaders();

    /**
     * What has arrived of a connection's request, kept on the connection until the request is answered; null once it
     * has been, while the connection closes.
     */
    private static final class Pending {
        private Buffer request = Buffer.buffer();
    }

    public HttpResponseFault(int status) {
        this.status = status;
    }

    /**
     * Set on the event loop, before the first request is answered.
     */
    public void setBody(String body) {
        if (bodyLength >= 0) {
            throw new IllegalStateException("body(...) cannot be combined with bodyOfLength(...)");
        }
        this.body = body;
    }

    public void setBodyOfLength(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("a body length cannot be negative, got " + bytes);
        }
        if (body != null) {
            throw new IllegalStateException("bodyOfLength(...) cannot be combined with body(...)");
        }
        this.bodyLength = bytes;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void addHeader(String name, String value) {
        headers.set(name, value);
    }

    @Override
    public String describe() {
        return "respondsWith " + status;
    }

    @Override
    public boolean readsHttp() {
        return true;
    }

    @Override
    public void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        // Nothing from the upstream is relayed; only the request direction drives a response.
        if (pipe.direction() != Direction.UPSTREAM) {
            return;
        }
        ProxyConnection connection = pipe.connection();
        Pending pending = (Pending) connection.faultState(this);
        if (pending == null) {
            pending = new Pending();
            connection.faultState(this, pending);
        }
        if (pending.request == null) {
            // The connection has had its one response and is closing: drop whatever still arrives (e.g. a body).
            return;
        }
        Buffer request = pending.request;
        request.appendBuffer(data);
        if (!endsRequestHeaders(marks) && request.length() <= MAX_REQUEST_HEADER) {
            return; // wait for the rest of the headers
        }
        pending.request = null;
        // before the response is written: the write can complete, and close the connection, before forward returns,
        // and a closed connection forgets the requests still in flight on it
        connection.answeredByFault(status);
        if (bodyLength < 0) {
            connection.toClient().forward(render(), connection::closeAfterResponse);
            return;
        }
        connection.toClient().forward(render(), () -> streamBody(connection, bodyLength));
    }

    /**
     * Writes the next piece of a streamed body once the previous one has been taken, then closes.
     */
    private static void streamBody(ProxyConnection connection, long remaining) {
        if (remaining == 0) {
            connection.closeAfterResponse();
            return;
        }
        int size = (int) Math.min(BODY_CHUNK, remaining);
        Buffer piece = size == BODY_CHUNK ? Buffer.buffer(FILLER) : Buffer.buffer(Arrays.copyOf(FILLER, size));
        connection.toClient().forward(piece, () -> streamBody(connection, remaining - size));
    }

    private static boolean endsRequestHeaders(List<HttpMark> marks) {
        for (HttpMark mark : marks) {
            if (mark.kind() == HttpMark.Kind.REQUEST_HEADERS) {
                return true;
            }
        }
        return false;
    }

    /**
     * The response as Netty's HTTP encoder writes it.
     */
    private Buffer render() {
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(bodyBytes));
        // a header the test set wins over the ones added here
        HttpHeaders all = response.headers().add(headers);
        if (contentType != null && !all.contains(HttpHeaderNames.CONTENT_TYPE)) {
            all.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        if (!all.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            all.set(HttpHeaderNames.CONTENT_LENGTH, bodyLength >= 0 ? bodyLength : bodyBytes.length);
        }
        if (!all.contains(HttpHeaderNames.CONNECTION)) {
            all.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        EmbeddedChannel encoder = new EmbeddedChannel(new HttpResponseEncoder());
        encoder.writeOutbound(response);
        Buffer rendered = Buffer.buffer();
        ByteBuf part = encoder.readOutbound();
        while (part != null) {
            rendered.appendBytes(ByteBufUtil.getBytes(part));
            part.release();
            part = encoder.readOutbound();
        }
        encoder.finishAndReleaseAll();
        return rendered;
    }
}
