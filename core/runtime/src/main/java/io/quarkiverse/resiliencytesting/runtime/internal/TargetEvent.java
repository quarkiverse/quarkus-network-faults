package io.quarkiverse.resiliencytesting.runtime.internal;

import java.time.Instant;

/**
 * One entry of a target's event log. {@code connectionId} is {@link #NO_CONNECTION} for events not tied to a
 * connection.
 */
public record TargetEvent(Instant time, Type type, long connectionId, String detail) {

    /**
     * The connection id of an event that is about the target rather than one of its connections; connection ids
     * start at one.
     */
    public static final long NO_CONNECTION = -1;

    public enum Type {
        LISTENER_BOUND,
        LISTENER_UNBOUND,
        FAULT_STARTED,
        FAULT_ENDED,
        CONNECTION_OPENED,
        CONNECTION_CLOSED,
        CONNECTION_RESET,
        CONNECTIONS_DROPPED,
        REQUEST,
        UPSTREAM_CONNECT_FAILED
    }
}
