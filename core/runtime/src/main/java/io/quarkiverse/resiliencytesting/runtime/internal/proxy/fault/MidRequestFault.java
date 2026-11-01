package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyConnection;
import io.vertx.core.buffer.Buffer;

/**
 * Ends the connection while a request is in flight, at the moment the {@link Moment} names: nothing of the response is
 * delivered for {@code afterBytes(0)}, or on the client's next write the request never reaches the upstream. Either
 * with a TCP reset, which a client reports as an error on the socket, or cleanly with a FIN, which it reports as an end
 * of stream and may take for a complete response when nothing told it the length.
 */
public final class MidRequestFault extends MomentFault {

    /**
     * How the connection ends.
     */
    public enum Ending {
        RESET,
        CLOSE
    }

    private final Ending ending;

    public MidRequestFault(Ending ending, Moment when) {
        super(when);
        this.ending = ending;
    }

    @Override
    public String describe() {
        return (ending == Ending.RESET ? "diesMidRequest " : "closes ") + moment();
    }

    @Override
    void strike(Pipe pipe, Buffer data, int cut) {
        ProxyConnection c = pipe.connection();
        if (cut == 0) {
            end(c);
            return;
        }
        // deliver exactly up to the position, then end the connection. Ending on this boundary buffer matters when
        // the response ends here and none follows.
        Buffer head = cut == data.length() ? data : data.slice(0, cut);
        pipe.forward(head, () -> end(c));
    }

    private void end(ProxyConnection c) {
        if (ending == Ending.RESET) {
            c.reset();
        } else {
            c.closeBoth("closed by " + describe());
        }
    }
}
