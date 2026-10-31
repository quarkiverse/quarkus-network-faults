package io.quarkiverse.networkfaults;

/**
 * The event loops of the application. They cannot be exhausted to any effect: with every loop blocked the server
 * answers nothing, the test's own request included. What a test can show is that the request under test does not
 * block one, which is harmless at low traffic and fatal under load.
 */
public interface EventLoops {

    /**
     * Watches the event loops until the returned fault is ended. Ending it fails with an {@link AssertionError}
     * naming every event loop that was blocked meanwhile for longer than
     * {@code quarkus.vertx.max-event-loop-execute-time}, which is 2 seconds by default. Lower it, along with
     * {@code quarkus.vertx.blocked-thread-check-interval}, in the test configuration.
     */
    ActiveFault watched();
}
