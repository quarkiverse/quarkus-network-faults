package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

/**
 * A server for tests of the proxy to stand in front of, on its own Vert.x instance so that the proxy is tested
 * against an independent peer: one that echoes, one that answers each request with the next of its payloads, or one
 * that writes payloads on its own and then holds the connection open.
 */
public final class TestUpstream implements AutoCloseable {

    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    private final NetServer server;

    private TestUpstream(Handler<NetSocket> onConnect) {
        server = vertx.createNetServer();
        server.connectHandler(onConnect);
        try {
            server.listen(0, "127.0.0.1").await(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Echoes every byte back.
     */
    public static TestUpstream echo() {
        return new TestUpstream(socket -> socket.pipeTo(socket));
    }

    /**
     * Answers whatever it receives on a connection with the next of the fixed payloads.
     */
    public static TestUpstream answering(byte[]... answers) {
        return new TestUpstream(socket -> {
            int[] next = { 0 };
            socket.handler(request -> socket.write(Buffer.buffer(answers[next[0]++])));
        });
    }

    /**
     * Answers the first data received, then closes normally once the response has been written.
     */
    public static TestUpstream answeringAndClosing(byte[] answer) {
        return new TestUpstream(socket -> socket.handler(request -> {
            socket.pause();
            socket.write(Buffer.buffer(answer)).onSuccess(v -> socket.close());
        }));
    }

    /**
     * Writes the fixed payloads to every connection it accepts, one write at a time 100 ms apart, then holds the
     * connection open.
     */
    public static TestUpstream scripted(byte[]... toSend) {
        TestUpstream[] self = new TestUpstream[1];
        self[0] = new TestUpstream(socket -> self[0].write(socket, toSend, 0));
        return self[0];
    }

    private void write(NetSocket socket, byte[][] toSend, int index) {
        socket.write(Buffer.buffer(toSend[index]));
        if (index + 1 < toSend.length) {
            vertx.setTimer(100, id -> write(socket, toSend, index + 1));
        }
    }

    public int port() {
        return server.actualPort();
    }

    @Override
    public void close() {
        try {
            vertx.close().await(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
