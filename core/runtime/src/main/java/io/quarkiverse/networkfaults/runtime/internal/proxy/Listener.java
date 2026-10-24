package io.quarkiverse.networkfaults.runtime.internal.proxy;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.quarkiverse.networkfaults.runtime.internal.TargetEvent;
import io.quarkiverse.networkfaults.spi.AddressUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;

/**
 * A target's listening side: the Vert.x server, or the {@link BlackHole} that takes its port while connects are
 * being dropped. Runs on the proxy event loop only; every transition is a future, never a blocking call, so the
 * loop stays free while a port is released or a black hole is filled.
 * <p>
 * {@code REFUSING} closes the server, which also closes every accepted socket. {@code DROPPING} must keep them:
 * Vert.x has no "stop accepting" call, so the server is shut down gracefully with a grace period longer than any
 * test (the port is released at once; the {@code shutdownHandler} each connection installs keeps its socket open)
 * and a black hole takes the port on the worker thread, since it is made of blocking JDK sockets.
 */
final class Listener {

    /**
     * How long taking the port again keeps being retried, and how often: a closed listener releases its port
     * asynchronously, within milliseconds, so the budget is only ever used up when something else took the port.
     */
    static final long REBIND_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(3);
    static final long REBIND_RETRY_MILLIS = 20;

    private final ProxyTarget target;
    private NetServer server;
    private BlackHole blackHole;
    private int port;

    Listener(ProxyTarget target, int port) {
        this.target = target;
        this.port = port;
    }

    private Vertx vertx() {
        return target.registry().vertx();
    }

    int port() {
        return port;
    }

    boolean isListening() {
        return server != null;
    }

    private ListenerState state() {
        if (blackHole != null) {
            return ListenerState.DROPPING;
        }
        return server != null ? ListenerState.LISTENING : ListenerState.REFUSING;
    }

    /**
     * Bind, or rebind on the same port; nothing to do while bound.
     */
    Future<Void> bind() {
        if (server != null) {
            return Future.succeededFuture();
        }
        NetServer s = vertx().createNetServer(new NetServerOptions()
                .setHost(AddressUtil.PROXY_HOST)
                .setPort(port));
        s.connectHandler(target::accept);
        return listen(s, System.nanoTime() + REBIND_BUDGET_NANOS).andThen(ar -> {
            if (ar.succeeded()) {
                server = s;
                port = s.actualPort();
                target.stats().log(TargetEvent.Type.LISTENER_BOUND, TargetEvent.NO_CONNECTION,
                        AddressUtil.PROXY_HOST + ":" + port
                                + " -> " + target.upstreamAddress());
            }
        }).mapEmpty();
    }

    /**
     * Listens, retrying a port still held by the listener that was just closed until the budget runs out.
     */
    private Future<NetServer> listen(NetServer s, long deadlineNanos) {
        return s.listen().recover(failure -> {
            if (!(failure instanceof BindException) || System.nanoTime() >= deadlineNanos) {
                return Future.failedFuture(failure);
            }
            return vertx().timer(REBIND_RETRY_MILLIS).compose(v -> listen(s, deadlineNanos));
        });
    }

    /**
     * Move to {@code state}, undoing whatever the previous state set up; falls back to listening on failure.
     */
    Future<Void> set(ListenerState state) {
        if (state == state()) {
            return Future.succeededFuture();
        }
        Future<Void> transition = switch (state) {
            case LISTENING -> closeBlackHole().compose(v -> bind());
            case REFUSING -> closeBlackHole().compose(v -> unbind(false));
            case DROPPING -> unbind(true).compose(v -> vertx()
                    .executeBlocking(() -> BlackHole.open(new InetSocketAddress(AddressUtil.PROXY_HOST, port)))
                    .andThen(ar -> {
                        if (ar.succeeded()) {
                            blackHole = ar.result();
                            target.stats().log(TargetEvent.Type.LISTENER_UNBOUND, TargetEvent.NO_CONNECTION,
                                    AddressUtil.PROXY_HOST + ":" + port + " (dropping SYNs)");
                        }
                    }).mapEmpty());
        };
        return transition.recover(failure -> closeBlackHole().compose(v -> bind())
                .transform(v -> Future.failedFuture(failure)));
    }

    Future<Void> close() {
        return closeBlackHole().compose(v -> unbind(false));
    }

    private Future<Void> unbind(boolean keepConnections) {
        NetServer s = server;
        if (s == null) {
            return Future.succeededFuture();
        }
        server = null;
        if (keepConnections) {
            s.shutdown(Duration.ofHours(24)); // completes only when the connections do; the port is free at once
            return Future.succeededFuture();
        }
        return s.close().andThen(ar -> target.stats().log(TargetEvent.Type.LISTENER_UNBOUND, TargetEvent.NO_CONNECTION,
                AddressUtil.PROXY_HOST + ":" + port));
    }

    private Future<Void> closeBlackHole() {
        BlackHole hole = blackHole;
        if (hole == null) {
            return Future.succeededFuture();
        }
        blackHole = null;
        return vertx().executeBlocking(() -> {
            hole.close();
            return null;
        });
    }
}
