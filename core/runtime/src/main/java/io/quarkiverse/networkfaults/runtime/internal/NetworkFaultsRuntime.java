package io.quarkiverse.networkfaults.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

import io.quarkiverse.networkfaults.runtime.internal.config.TargetCatalog;
import io.quarkiverse.networkfaults.runtime.internal.proxy.ProxyRegistry;
import io.quarkiverse.networkfaults.spi.AddressRewriter;
import io.quarkiverse.networkfaults.spi.TargetDescriptor;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.Upstream;

/**
 * Process-wide holder of the {@link ProxyRegistry}. The configuration interceptor may need it before any bean or
 * recorder has run, so it is created lazily and closed from the application shutdown hook.
 * <p>
 * <b>Threads.</b> The catalog and the default properties are written once, at static init on the main thread,
 * before any lookup; they are plain volatile reads after that. Everything else is reached from whichever thread
 * resolves a configuration property: application threads, worker threads, the application's event loop, a
 * virtual thread. One {@link ReentrantLock} guards the two check-then-acts that are this class's own: creating the
 * registry, and the miss path of the rewrite cache. It is a lock rather than a monitor because a rewrite creates
 * targets, which waits for the proxy's event loop to bind, and a virtual thread that blocks inside
 * {@code synchronized} pins its carrier. The cache's hit path and {@link #isStarted} take no lock. Which target an
 * address gets is the registry's decision, taken under the registry's own lock: a rewriter runs inside
 * {@link #rewrite}'s critical section and calls {@link #proxyPort} from there, so the two locks are always taken in
 * that order. {@link #close} drops the registry under the lock and closes it outside, since closing waits for the
 * proxy's event loop.
 */
public final class NetworkFaultsRuntime {

    private static final Logger LOG = Logger.getLogger(NetworkFaultsRuntime.class);

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile ProxyRegistry registry;
    private static volatile TargetCatalog catalog = TargetCatalog.of(List.of());
    private static final Map<String, String> REWRITES = new ConcurrentHashMap<>();
    private static volatile Map<String, String> defaultProperties = Map.of();

    private NetworkFaultsRuntime() {
    }

    public static ProxyRegistry registry() {
        ProxyRegistry r = registry;
        if (r == null) {
            LOCK.lock();
            try {
                r = registry;
                if (r == null) {
                    r = new ProxyRegistry();
                    registry = r;
                }
            } finally {
                LOCK.unlock();
            }
        }
        return r;
    }

    public static boolean isStarted() {
        ProxyRegistry r = registry;
        return r != null && !r.isClosed();
    }

    public static void close() {
        ProxyRegistry r;
        LOCK.lock();
        try {
            r = registry;
            registry = null;
            REWRITES.clear();
        } finally {
            LOCK.unlock();
        }
        if (r != null) {
            r.close();
        }
    }

    /**
     * Rewrite a configuration value to point at proxies, creating them as needed. Stable per property and value.
     * Creating a proxy binds a socket, so it happens under the lock rather than inside the cache's own bin lock.
     */
    public static String rewrite(TargetCatalog.Match match, String value, AddressRewriter.Context context) {
        String key = match.property() + '=' + value;
        String cached = REWRITES.get(key);
        if (cached != null) {
            return cached;
        }
        LOCK.lock();
        try {
            cached = REWRITES.get(key);
            if (cached != null) {
                return cached;
            }
            String rewritten = match.rewriter().rewrite(value, context);
            if (rewritten == null) {
                LOG.warnf("network faults: cannot proxy %s (unsupported form); leaving it untouched", match.property());
                rewritten = value;
            } else if (rewritten.equals(value)) {
                LOG.debugf("network faults: %s already points at a proxy", match.property());
            } else {
                // Endpoint values can carry credentials in user info, query strings or driver-specific options.
                LOG.infof("network faults: %s now points at a proxy", match.property());
            }
            REWRITES.put(key, rewritten);
            return rewritten;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The port of the proxy standing in for one upstream address of a matched property; the registry decides
     * which proxy that is and creates it on first sight.
     */
    public static int proxyPort(TargetCatalog.Match match, Upstream upstream, int port) {
        TargetName name = match.targetName().substitute(
                Map.of(TargetName.HOST, upstream.host(), TargetName.PORT, Integer.toString(upstream.port())));
        return registry().targetFor(name, upstream, match.property(), port).port();
    }

    /**
     * The targets every extension declared at build time; runs at static init, before the runtime configuration.
     */
    public static void registerTargets(List<TargetDescriptor> descriptors) {
        catalog = TargetCatalog.of(descriptors);
    }

    public static TargetCatalog catalog() {
        return catalog;
    }

    /**
     * Values found at build time for address properties the application does not configure, such as a REST
     * Client's {@code @RegisterRestClient(baseUri)}: served from a low-ordinal source so they take the same path.
     */
    public static void registerDefaultProperties(Map<String, String> properties) {
        defaultProperties = Map.copyOf(properties);
    }

    public static Map<String, String> defaultProperties() {
        return defaultProperties;
    }

}
