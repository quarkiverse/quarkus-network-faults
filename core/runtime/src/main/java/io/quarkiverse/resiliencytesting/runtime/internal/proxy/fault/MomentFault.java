package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.util.List;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Direction;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyConnection;
import io.vertx.core.buffer.Buffer;

/**
 * A fault that relays until the {@link Moment} it names, then strikes: on the client's next write, before any of it is
 * relayed, or at a position in a response, which a {@link ResponseCursor} kept on each connection looks for. What it
 * does there is up to the fault.
 */
abstract class MomentFault implements Fault {

    private final Moment when;

    MomentFault(Moment when) {
        this.when = when;
    }

    /**
     * The moment in words, for a fault's description.
     */
    String moment() {
        return ResponseCursor.describe(when);
    }

    @Override
    public boolean readsHttp() {
        return ResponseCursor.readsHttp(when);
    }

    @Override
    public final void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        boolean fromClient = pipe.direction() == Direction.UPSTREAM;
        if (when instanceof Moment.OnNextWrite) {
            if (fromClient) {
                strike(pipe, data, 0);
            } else {
                pipe.forward(data);
            }
            return;
        }
        if (fromClient) {
            pipe.forward(data);
            return;
        }
        ProxyConnection c = pipe.connection();
        ResponseCursor cursor = (ResponseCursor) c.faultState(this);
        if (cursor == null) {
            cursor = ResponseCursor.of(when);
            c.faultState(this, cursor);
        }
        int cut = cursor.scan(data, marks);
        if (cut < 0) {
            pipe.forward(data);
            return;
        }
        strike(pipe, data, cut);
    }

    /**
     * Strike in {@code data}, the moment being just past its first {@code cut} bytes, which are relayed before it.
     */
    abstract void strike(Pipe pipe, Buffer data, int cut);
}
