package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.util.List;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyConnection;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;
import io.vertx.core.buffer.Buffer;

/**
 * Connections stay open but nothing is relayed. On the whole target every connection is held as the fault starts and
 * as it opens, so no data arrives; on some requests a connection is held when the first buffer of one of them
 * arrives instead. Everything held is delivered when the fault ends.
 */
public final class HangFault implements Fault {

    @Override
    public String describe() {
        return "hangs";
    }

    @Override
    public void onStart(ProxyTarget target) {
        for (ProxyConnection c : target.liveConnections()) {
            Holding.hold(c, this);
        }
    }

    @Override
    public void onEnd(ProxyTarget target) {
        Holding.releaseAll(target, this);
    }

    @Override
    public void onConnectionOpened(ProxyConnection connection) {
        Holding.hold(connection, this);
    }

    @Override
    public void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        Holding.hold(pipe, data, 0, this);
    }
}
