package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;
import io.vertx.core.buffer.Buffer;

/**
 * Relays until the position the {@link Moment} names, then the connection hangs like under
 * {@link HangFault}: reading stops on both sides so TCP backpressure builds up. The rest of the buffer in which the
 * position was reached is kept and delivered, ahead of everything else, when the fault ends.
 */
public final class HangMidRequestFault extends MomentFault {

    public HangMidRequestFault(Moment when) {
        super(when);
    }

    @Override
    public String describe() {
        return "hangs " + moment();
    }

    @Override
    public void onEnd(ProxyTarget target) {
        Holding.releaseAll(target, this);
    }

    @Override
    void strike(Pipe pipe, Buffer data, int cut) {
        Holding.hold(pipe, data, cut, this);
    }
}
