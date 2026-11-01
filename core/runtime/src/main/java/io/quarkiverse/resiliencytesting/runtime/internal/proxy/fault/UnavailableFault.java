package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ListenerState;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyTarget;

/**
 * The upstream is down: the listener goes away (ECONNREFUSED for new connections) and live connections are reset.
 */
public final class UnavailableFault implements Fault {

    @Override
    public String describe() {
        return "unavailable";
    }

    @Override
    public boolean observable() {
        return false;
    }

    @Override
    public ListenerState listenerState() {
        return ListenerState.REFUSING;
    }

    @Override
    public void onStart(ProxyTarget target) {
        target.resetConnections();
    }
}
