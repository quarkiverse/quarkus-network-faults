package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import io.netty.channel.ChannelOption;
import io.quarkiverse.resiliencytesting.runtime.internal.ConnectionSnapshot;
import io.vertx.core.Vertx;
import io.vertx.core.internal.net.NetSocketInternal;
import io.vertx.core.net.NetSocket;

/**
 * A client connection accepted by a {@link ProxyTarget} and its matching upstream connection.
 */
public final class ProxyConnection {

    private final long id;
    private final ProxyTarget target;
    private final NetSocket frontend;
    private final String clientAddress;
    private final Instant openedAt = Instant.now();
    private long bytesUpstream;
    private long bytesDownstream;
    private long lastActivityMillis = System.currentTimeMillis();
    private boolean closed;
    private boolean upstreamClosed;
    private NetSocket backend;
    private Pipe toUpstream;
    private Pipe toClient;
    private final HttpConversation http;
    // what each fault, or the target's routing of a fault, keeps about this connection
    private final Map<Object, Object> faultStates = new HashMap<>();

    ProxyConnection(long id, ProxyTarget target, NetSocket frontend) {
        this.id = id;
        this.target = target;
        this.frontend = frontend;
        this.clientAddress = String.valueOf(frontend.remoteAddress());
        this.http = new HttpConversation(this);
    }

    public long id() {
        return id;
    }

    public ProxyTarget target() {
        return target;
    }

    public Pipe toUpstream() {
        return toUpstream;
    }

    public Pipe toClient() {
        return toClient;
    }

    /**
     * The HTTP conversation on this connection, which is left alone unless the target is HTTP.
     */
    HttpConversation http() {
        return http;
    }

    /**
     * A fault answered the request in flight on this connection with {@code status} in place of the upstream, which
     * the request's record then carries.
     */
    public void answeredByFault(int status) {
        http.answeredByFault(status);
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * What {@code owner}, a fault or the target's routing of one, keeps about this connection, or null when it keeps
     * nothing yet. It lives here rather than in the owner so that it goes away with the connection; the target clears
     * it when the fault ends. Each owner has its own, since faults on different requests act on the same connection.
     */
    public Object faultState(Object owner) {
        return faultStates.get(owner);
    }

    /**
     * Keeps {@code state} for {@code owner}, or forgets what it kept when null.
     */
    public void faultState(Object owner, Object state) {
        if (state == null) {
            faultStates.remove(owner);
        } else {
            faultStates.put(owner, state);
        }
    }

    String clientAddress() {
        return clientAddress;
    }

    Vertx vertx() {
        return target.registry().vertx();
    }

    void start() {
        frontend.pause();
        // A server shutdown (see Listener.unbind) must not take established connections down. Vert.x routes
        // both a server shutdown (with its grace period) and an explicit close() of the socket (grace zero)
        // through this handler; only the latter closes.
        frontend.shutdownHandler(grace -> {
            if (grace.isZero()) {
                frontend.close();
            }
        });
        frontend.closeHandler(v -> closeBoth("client closed"));
        frontend.exceptionHandler(t -> closeBoth("client error: " + t));
        target.registry().client().connect(target.upstream().port(), target.upstream().host())
                .onComplete(ar -> {
                    if (ar.failed()) {
                        target.upstreamConnectFailed(this, ar.cause());
                        reset();
                        return;
                    }
                    backend = ar.result();
                    backend.pause();
                    toUpstream = new Pipe(this, Direction.UPSTREAM, frontend, backend);
                    toClient = new Pipe(this, Direction.DOWNSTREAM, backend, frontend);
                    frontend.handler(buf -> {
                        if (!closed && !upstreamClosed) {
                            toUpstream.onData(buf);
                        }
                    });
                    backend.handler(buf -> {
                        if (!closed) {
                            toClient.onData(buf);
                        }
                    });
                    backend.closeHandler(v -> upstreamClosed());
                    // A close can precede unread buffers while the source is paused. Only endHandler means
                    // that every response buffer has reached the pipe, whose delayed writes must then drain.
                    backend.endHandler(v -> toClient.finish(() -> closeBoth("upstream closed")));
                    backend.exceptionHandler(t -> closeBoth("upstream error: " + t));
                    frontend.drainHandler(v -> toClient.onSinkWritabilityChanged());
                    backend.drainHandler(v -> toUpstream.onSinkWritabilityChanged());
                    if (closed) {
                        backend.close();
                        return;
                    }
                    target.connectionOpened(this);
                    target.connectionFault().onConnectionOpened(this);
                    if (!toUpstream.isHolding()) {
                        frontend.resume();
                    }
                    if (!toClient.isHolding()) {
                        backend.resume();
                    }
                });
    }

    private void upstreamClosed() {
        if (closed) {
            return;
        }
        upstreamClosed = true;
        frontend.pause();
        toUpstream.drop();
    }

    /**
     * Abort both sides with a TCP reset.
     */
    public void reset() {
        if (closed) {
            return;
        }
        closed = true;
        abort(frontend);
        if (backend != null) {
            abort(backend);
        }
        dropHeld();
        target.connectionReset(this);
    }

    /**
     * Close with {@code SO_LINGER=0} so the peer sees a TCP reset instead of a clean end of stream. Vert.x has no
     * public API for a reset, so this is the one place that reaches the socket's channel through
     * {@link NetSocketInternal}. A socket that is already closed is left alone.
     */
    private static void abort(NetSocket socket) {
        if (socket instanceof NetSocketInternal internal) {
            var channel = internal.channelHandlerContext().channel();
            if (channel.isOpen()) {
                channel.config().setOption(ChannelOption.SO_LINGER, 0);
            }
        }
        socket.close();
    }

    /**
     * Close both sides cleanly (a FIN, not a reset) once a synthetic response has been written to the client: the
     * client reads the whole response, then end of stream.
     */
    public void closeAfterResponse() {
        closeBoth("synthetic response sent");
    }

    /**
     * Close both sides cleanly, a FIN and not a reset, logging {@code reason} with the connection's closing.
     */
    public void closeBoth(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        dropHeld();
        frontend.close();
        if (backend != null) {
            backend.close();
        }
        target.connectionClosed(this, reason);
    }

    private void dropHeld() {
        if (toUpstream != null) {
            toUpstream.drop();
        }
        if (toClient != null) {
            toClient.drop();
        }
    }

    void recordBytes(Direction direction, int size) {
        lastActivityMillis = System.currentTimeMillis();
        if (direction == Direction.UPSTREAM) {
            bytesUpstream += size;
        } else {
            bytesDownstream += size;
        }
        target.stats().bytes(direction, size);
    }

    void requestSeen(HttpExchange request) {
        lastActivityMillis = System.currentTimeMillis();
        target.stats().requestSeen(id, request);
    }

    /**
     * Whether a fault is holding either side; on the loop, for a connection in the target's map.
     */
    boolean isHeld() {
        return toUpstream.isHolding() || toClient.isHolding();
    }

    /**
     * On the loop, for a connection in the target's map: both pipes exist by then.
     */
    ConnectionSnapshot snapshot() {
        return new ConnectionSnapshot(id, clientAddress, openedAt,
                Instant.ofEpochMilli(lastActivityMillis), bytesUpstream, bytesDownstream, isHeld());
    }
}
