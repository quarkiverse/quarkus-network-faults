package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Fault;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ListenerState;

/**
 * New connection attempts hang until the client's connect timeout fires; established connections keep working.
 * The mechanics (Linux accept-queue drop) live in {@code BlackHole}, driven by the target's listener state.
 */
public final class ConnectTimeoutFault implements Fault {

    @Override
    public String describe() {
        return "connectTimeout";
    }

    @Override
    public boolean observable() {
        return false;
    }

    @Override
    public ListenerState listenerState() {
        return ListenerState.DROPPING;
    }
}
