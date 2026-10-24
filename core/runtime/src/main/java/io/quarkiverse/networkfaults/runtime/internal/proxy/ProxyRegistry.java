package io.quarkiverse.networkfaults.runtime.internal.proxy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import io.quarkiverse.networkfaults.spi.AddressUtil;
import io.quarkiverse.networkfaults.spi.TargetName;
import io.quarkiverse.networkfaults.spi.Upstream;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.file.FileSystemOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

/**
 * Owns the proxy's Vert.x instance and every {@link ProxyTarget}.
 * <p>
 * The instance is private to the proxy: the configuration interceptor creates targets before the application's
 * Vert.x exists, so the proxy cannot borrow it. One event loop is enough, and it never blocks.
 */
public final class ProxyRegistry {

    /**
     * How many events, and how many HTTP requests, a target remembers; older ones are dropped.
     */
    public static final int DEFAULT_EVENT_CAPACITY = 500;
    /**
     * How long the proxy tries to reach an upstream before it gives up and resets the application's connection.
     * The application is connected to the proxy by then, so without a bound it would wait out its read timeout on
     * an upstream that never answers. Ten seconds is about what clients allow for a connect themselves: the
     * PostgreSQL driver 10, the REST Client 15.
     */
    private static final int UPSTREAM_CONNECT_TIMEOUT_MILLIS = 10_000;
    /**
     * How long a caller waits for the proxy's event loop. The loop never blocks, so this is only reached when
     * something is stuck; it is longer than the slowest thing waited for, which is taking a port to drop connects
     * on: the rebind budget plus every filler connection timing out, under twenty seconds.
     */
    private static final int LOOP_WAIT_SECONDS = 30;
    private static final AtomicInteger LIVE = new AtomicInteger();

    private final int eventCapacity;
    private final Vertx vertx;
    private final Context context;
    private final NetClient client;
    private final Map<TargetName, ProxyTarget> targets = new ConcurrentHashMap<>();
    /**
     * Guards the lookup and creation of targets, and closing.
     */
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    public ProxyRegistry() {
        this(DEFAULT_EVENT_CAPACITY);
    }

    public ProxyRegistry(int eventCapacity) {
        this.eventCapacity = eventCapacity;
        this.vertx = Vertx.vertx(new VertxOptions()
                .setEventLoopPoolSize(1)
                .setWorkerPoolSize(1)
                .setInternalBlockingPoolSize(1)
                // the proxy never touches the file system: skip the class-path cache directory Vert.x creates
                .setFileSystemOptions(new FileSystemOptions().setClassPathResolvingEnabled(false))
                // faults are applied from test threads as messages to the loop; the loop itself never blocks
                .setBlockedThreadCheckInterval(1).setBlockedThreadCheckIntervalUnit(TimeUnit.HOURS));
        this.context = vertx.getOrCreateContext();
        this.client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MILLIS));
        LIVE.incrementAndGet();
    }

    /**
     * Registries currently open in this classloader; tests use it to prove restart hygiene.
     */
    public static int liveInstances() {
        return LIVE.get();
    }

    Vertx vertx() {
        return vertx;
    }

    NetClient client() {
        return client;
    }

    private boolean onLoop() {
        Context current = Vertx.currentContext();
        return current != null && current.owner() == vertx;
    }

    /**
     * Like {@link #call}, for something to do on the loop that yields nothing.
     */
    public void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Compute a value on the proxy's event loop and wait for it; runs inline when already on the loop.
     */
    public <T> T call(Supplier<T> task) {
        if (onLoop()) {
            return task.get();
        }
        Promise<T> done = Promise.promise();
        context.runOnContext(v -> {
            try {
                done.complete(task.get());
            } catch (Throwable t) {
                done.fail(t);
            }
        });
        return await(done.future());
    }

    /**
     * Start an asynchronous action on the loop and wait for its outcome. The loop never waits for itself.
     */
    public <T> T callAsync(Supplier<Future<T>> action) {
        if (onLoop()) {
            throw new IllegalStateException("the proxy event loop cannot wait for itself");
        }
        Promise<T> done = Promise.promise();
        context.runOnContext(v -> {
            try {
                action.get().onComplete(done);
            } catch (Throwable t) {
                done.fail(t);
            }
        });
        return await(done.future());
    }

    /**
     * Block a caller thread on a Vert.x future. Deliberately not {@code Future.await}: that refuses to run on any
     * Vert.x thread, and a configuration lookup may happen on the application's event loop.
     */
    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(LOOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("timed out waiting for the proxy", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The target standing in for one upstream address, created and bound on first sight.
     * <p>
     * An upstream that is itself one of the proxies means the value was already rewritten under another key, since
     * configuration fallbacks resolve through the full chain: that proxy is the target, rather than a proxy of the
     * proxy. Otherwise the target is the one called {@code name} if it fronts this upstream or the name is free,
     * else the first {@code name#n} of which that is true.
     * <p>
     * All of it happens under the registry's lock, so that two lookups cannot both create a target. It is a lock
     * rather than a monitor because binding waits for the loop, and a virtual thread that blocks inside
     * {@code synchronized} pins its carrier.
     *
     * @throws IllegalStateException if the registry is closed
     */
    public ProxyTarget targetFor(TargetName name, Upstream upstream, String sourceProperty) {
        return targetFor(name, upstream, sourceProperty, 0);
    }

    /**
     * Like {@link #targetFor(TargetName, Upstream, String)}, binding the proxy to {@code port} when it is not zero.
     */
    public ProxyTarget targetFor(TargetName name, Upstream upstream, String sourceProperty, int port) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("proxy registry is closed");
            }
            if (upstream.host().equals(AddressUtil.PROXY_HOST)) {
                ProxyTarget proxy = listeningOn(upstream.port());
                if (proxy != null) {
                    return proxy;
                }
            }
            TargetName free = name;
            ProxyTarget taken = targets.get(free);
            for (int n = 2; taken != null; n++) {
                if (taken.upstream().host().equals(upstream.host()) && taken.upstream().port() == upstream.port()) {
                    return taken;
                }
                free = name.withInstance(n);
                taken = targets.get(free);
            }
            ProxyTarget created = new ProxyTarget(this, free, upstream, sourceProperty, eventCapacity, port);
            created.bind();
            targets.put(free, created);
            return created;
        } finally {
            lock.unlock();
        }
    }

    private ProxyTarget listeningOn(int port) {
        for (ProxyTarget t : targets.values()) {
            if (t.port() == port) {
                return t;
            }
        }
        return null;
    }

    public Collection<ProxyTarget> targets() {
        return List.copyOf(targets.values());
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Closes every target and the Vert.x instance; called once, from the application's shutdown hook.
     */
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lock.unlock();
        }
        try {
            for (ProxyTarget t : targets.values()) {
                try {
                    t.close();
                } catch (RuntimeException ignored) {
                    // closing Vert.x below takes the rest down
                }
            }
            targets.clear();
        } finally {
            await(vertx.close());
            LIVE.decrementAndGet();
        }
    }
}
