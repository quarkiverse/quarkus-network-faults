package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

/**
 * What a target's listening socket does with new connection attempts. Faults declare the state they need and
 * {@link Listener#set} performs the transitions, including the way back on failure.
 */
public enum ListenerState {
    /**
     * Accepting and relaying.
     */
    LISTENING,
    /**
     * No listener: connection attempts are refused with ECONNREFUSED.
     */
    REFUSING,
    /**
     * A listener whose accept queue is full and never drained: SYNs are dropped, the client's connect times out.
     */
    DROPPING
}
