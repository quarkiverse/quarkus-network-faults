package io.quarkiverse.resiliencytesting.runtime.internal;

import java.util.List;

import io.quarkiverse.resiliencytesting.Target;

/**
 * A target with the observation and control the Dev UI and the extension's own tests use; not part of the API.
 */
public interface ObservableTarget extends Target {

    /**
     * The faults active right now, {@code none} when idle; a target made of several proxies lists them all.
     */
    String currentFault();

    /**
     * The event log of every proxy behind this target, oldest first.
     */
    List<TargetEvent> events();

    /**
     * Ends every active fault without going through their handles.
     */
    void reset();

    /**
     * This target as the Dev UI applies faults to it: a fault on the whole target first ends whatever fault the
     * target has, so switching faults is one step, after the checks that could refuse the new fault have passed. A
     * fault on some of its requests ends nothing, as through the API.
     */
    ObservableTarget replacing();

    /**
     * The members not reached over plaintext HTTP/1.x, as {@code name (property)}, for the reasons the Dev UI gives
     * when it cannot show requests or apply an HTTP fault; empty when every member is.
     */
    List<String> opaqueMembers();
}
