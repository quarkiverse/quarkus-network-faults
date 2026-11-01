package io.quarkiverse.networkfaults.vertx.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.quarkiverse.networkfaults.spi.EventLoopWatcher;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.impl.VertxThread;
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.internal.threadchecker.BlockedThreadChecker;
import io.vertx.core.internal.threadchecker.BlockedThreadEvent;

/**
 * Watches the event loops through Vert.x's own blocked-thread checker, the one behind the "Thread ... has been
 * blocked" warning in production. It reports a thread that has run one task for longer than
 * {@code quarkus.vertx.max-event-loop-execute-time}, every {@code quarkus.vertx.blocked-thread-check-interval}
 * while it stays blocked; the last report per event loop is kept.
 */
@Singleton
public class VertxEventLoopWatcher implements EventLoopWatcher {

    private static final Logger LOG = Logger.getLogger(BlockedThreadChecker.LOGGER_NAME);

    /**
     * Vert.x's own handler cannot be read back once replaced, so this stands in for it after a watch: the warning
     * it logs, with the blocked thread's stack trace once the block exceeds {@code warning-exception-time}.
     */
    private static final Handler<BlockedThreadEvent> LOG_AS_VERTX_DOES = event -> {
        if (event.duration() <= event.warningExceptionTime()) {
            LOG.warn(describe(event));
        } else {
            VertxException stackTrace = new VertxException("Thread blocked");
            stackTrace.setStackTrace(event.thread().getStackTrace());
            LOG.warn(describe(event), stackTrace);
        }
    };

    private static String describe(BlockedThreadEvent event) {
        return "Thread " + event.thread().getName() + " has been blocked for "
                + TimeUnit.NANOSECONDS.toMillis(event.duration()) + " ms, time limit is "
                + TimeUnit.NANOSECONDS.toMillis(event.maxExecTime()) + " ms";
    }

    private final BlockedThreadChecker checker;

    public VertxEventLoopWatcher(Vertx vertx) {
        this.checker = ((VertxInternal) vertx).blockedThreadChecker();
    }

    @Override
    public Watch watch() {
        // event loop name -> the latest report about it
        Map<String, String> blocked = new ConcurrentHashMap<>();
        checker.setThreadBlockedHandler(event -> {
            if (event.thread() instanceof VertxThread thread && !thread.isWorker()) {
                blocked.put(thread.getName(), describe(event));
            }
        });
        return () -> {
            checker.setThreadBlockedHandler(LOG_AS_VERTX_DOES);
            return List.copyOf(new TreeMap<>(blocked).values());
        };
    }
}
