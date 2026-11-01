package io.quarkiverse.resiliencytesting.runtime.internal;

/**
 * Told when a fault handed out by {@link FaultsImpl} starts and when it ends, whichever way it ended. Not
 * part of the API: the Dev UI keeps its fault history with it.
 */
public interface FaultListener {

    /**
     * The fault has been applied to every target in {@code record}.
     */
    void started(FaultRecord record);

    /**
     * The fault has ended and been settled: its consequences are cleaned up and the no-traffic guard has judged it.
     */
    void ended(FaultRecord record, FaultEnd end);
}
