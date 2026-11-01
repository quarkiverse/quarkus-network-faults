package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.List;

import io.vertx.core.buffer.Buffer;

/**
 * A fault applied to a {@link ProxyTarget}. Every method runs on the proxy event loop, {@link #onStart} and
 * {@link #onEnd} included, so none of them may block.
 * <p>
 * A fault applied to some requests of a target is handed the buffers of those requests and of their responses only,
 * and ended; what acts on connections or on the listener, {@link #listenerState}, {@link #onStart} and
 * {@link #onConnectionOpened}, is left out, since every connection carries other requests too.
 */
public interface Fault {

    Fault NONE = new Fault() {
        @Override
        public String describe() {
            return "none";
        }
    };

    String describe();

    /**
     * Whether the proxy can observe traffic while this fault is active (false for refused/dropped connects).
     */
    default boolean observable() {
        return true;
    }

    /**
     * Whether this fault acts on the HTTP exchange, and so can only be applied to a target that is plaintext HTTP.
     */
    default boolean readsHttp() {
        return false;
    }

    /**
     * What the target's listener should do while this fault is active; restored when it ends.
     */
    default ListenerState listenerState() {
        return ListenerState.LISTENING;
    }

    default void onStart(ProxyTarget target) {
    }

    default void onEnd(ProxyTarget target) {
    }

    /**
     * Called on the event loop once the upstream connection is established, before reading starts.
     */
    default void onConnectionOpened(ProxyConnection connection) {
    }

    /**
     * Called on the event loop with a buffer read from {@code pipe}'s source, and the marks the connection's HTTP
     * conversation found in that buffer; none unless the target is HTTP.
     */
    default void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        pipe.forward(data);
    }
}
