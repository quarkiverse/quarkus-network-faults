package io.quarkiverse.networkfaults.spi;

import java.util.List;

/**
 * Provided by the extension that knows the application's event loops.
 */
public interface EventLoopWatcher {

    Watch watch();

    interface Watch {

        /**
         * Stops watching and describes the event loops blocked meanwhile, one line each; empty if none was.
         */
        List<String> stop();
    }
}
