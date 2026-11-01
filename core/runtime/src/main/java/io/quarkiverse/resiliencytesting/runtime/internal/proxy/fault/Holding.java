package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyConnection;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;
import io.vertx.core.buffer.Buffer;

/**
 * Holding a connection for the faults that hang: reading stops on both sides so TCP backpressure builds up instead of
 * the proxy buffering, and the part of a buffer the fault would not relay is kept on the connection and delivered,
 * ahead of everything else, when the fault ends. A fault releases the connections it held, and no other.
 */
final class Holding {

    // what a fault holds on a connection: the rest of a buffer to deliver into a pipe, or nothing
    private record Held(Pipe pipe, Buffer tail) {
    }

    private Holding() {
    }

    /**
     * Stop reading on both sides of the connection, for {@code fault}.
     */
    static void hold(ProxyConnection c, Fault fault) {
        c.toUpstream().startHolding();
        c.toClient().startHolding();
        c.faultState(fault, new Held(null, null));
    }

    /**
     * Forward {@code data} up to {@code cut}, hold the connection for {@code fault}, and keep the rest for when the
     * fault ends.
     */
    static void hold(Pipe pipe, Buffer data, int cut, Fault fault) {
        ProxyConnection c = pipe.connection();
        if (cut > 0) {
            pipe.forward(cut == data.length() ? data : data.slice(0, cut));
        }
        hold(c, fault);
        if (cut < data.length()) {
            // a held connection delivers nothing more, so its state from here on is what to hand over at the end
            c.faultState(fault, new Held(pipe, data.slice(cut, data.length())));
        }
    }

    /**
     * Deliver what every connection {@code fault} held kept, and read again.
     */
    static void releaseAll(ProxyTarget target, Fault fault) {
        for (ProxyConnection c : target.liveConnections()) {
            if (c.faultState(fault) instanceof Held(Pipe pipe, Buffer tail)) {
                if (tail != null) {
                    pipe.forward(tail);
                }
                c.toUpstream().release();
                c.toClient().release();
            }
        }
    }
}
