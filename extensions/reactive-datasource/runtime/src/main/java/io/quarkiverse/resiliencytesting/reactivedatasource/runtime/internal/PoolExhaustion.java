package io.quarkiverse.resiliencytesting.reactivedatasource.runtime.internal;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.resiliencytesting.datasource.ContentionSampler;
import io.quarkiverse.resiliencytesting.spi.Exhaustion;
import io.quarkus.arc.ClientProxy;
import io.vertx.core.Future;
import io.vertx.core.internal.CloseableResource;
import io.vertx.core.internal.pool.ConnectionPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.impl.pool.CloseablePool;
import io.vertx.sqlclient.impl.pool.PoolImpl;
import io.vertx.sqlclient.impl.pool.SqlConnectionPool;

/**
 * Every connection of a reactive pool, taken and held, so the application's own queries wait for the connection
 * timeout and fail as they do when real load has used the pool up.
 * <p>
 * A {@link ContentionSampler} watches the pool's waiter count while the connections are held, as the Agroal fault
 * does. The count is kept by the {@link ConnectionPool} at the bottom of the client's pool classes, which nothing
 * public exposes; see {@link #connectionPool(String, Pool)}. A pool whose callers never wait, or wait too briefly to
 * watch, has no sampler, and no guard.
 */
final class PoolExhaustion implements Exhaustion {

    /**
     * How long taking a connection may wait when the pool's own connection timeout does not bound it, which is the
     * case when the application holds a connection and the connection timeout is longer, or zero.
     */
    private static final Duration SETUP_WAIT = Duration.ofSeconds(10);

    private final List<SqlConnection> held = new ArrayList<>();
    // null when callers never wait for this pool, or wait too briefly to watch
    private final ContentionSampler sampler;

    private PoolExhaustion(ContentionSampler sampler) {
        this.sampler = sampler;
    }

    /**
     * @param callerWait how long a caller finding no free connection waits for one, zero for as long as it takes;
     *        empty when the pool turns such a caller away at once
     */
    static PoolExhaustion take(String target, Pool pool, int maxSize, Optional<Duration> callerWait) {
        ContentionSampler sampler = null;
        if (callerWait.isPresent() && ContentionSampler.canWatch(callerWait.get())) {
            ConnectionPool<?> connectionPool = connectionPool(target, pool);
            sampler = new ContentionSampler(target, new ContentionSampler.WaiterCount() {
                @Override
                public long now() {
                    return connectionPool.waiters();
                }
            });
        }
        PoolExhaustion exhaustion = new PoolExhaustion(sampler);
        for (int i = 0; i < maxSize; i++) {
            Future<SqlConnection> connection = pool.getConnection();
            try {
                exhaustion.held.add(connection.await(SETUP_WAIT));
            } catch (Exception e) {
                // the pool refused, or only this wait gave up: then the request stays queued for the pool's own,
                // longer timeout, and a connection it receives after that goes straight back
                connection.onSuccess(SqlConnection::close);
                exhaustion.release();
                throw new IllegalStateException("Could not take all " + maxSize + " connections of " + target
                        + ": the application holds some of them and did not give them back in time", e);
            }
        }
        if (sampler != null) {
            sampler.start();
        }
        return exhaustion;
    }

    /**
     * The connection pool under a {@code Pool} bean. The bean is a client proxy; the driver wraps the
     * {@link PoolImpl} it builds in a {@link CloseablePool}, directly or, from Vert.x 5.2, behind a
     * {@link CloseableResource}; the {@link PoolImpl} runs its queries on a {@link SqlConnectionPool}, and that keeps
     * its connections and waiters in a {@link ConnectionPool}. None of the three is reachable without reflection.
     */
    private static ConnectionPool<?> connectionPool(String target, Pool pool) {
        try {
            Object unwrapped = ClientProxy.unwrap(pool);
            if (unwrapped instanceof CloseablePool closeable) {
                unwrapped = field(closeable, CloseablePool.class, "delegate");
                if (unwrapped instanceof CloseableResource<?> resource) {
                    unwrapped = resource.get();
                }
            }
            if (!(unwrapped instanceof PoolImpl impl)) {
                throw new IllegalStateException("The pool of " + target + " is a " + unwrapped.getClass().getName()
                        + ", not the Vert.x SQL client's own: whether anything waits for it cannot be watched");
            }
            SqlConnectionPool sqlConnectionPool = (SqlConnectionPool) field(impl, PoolImpl.class, "pool");
            return (ConnectionPool<?>) field(sqlConnectionPool, SqlConnectionPool.class, "pool");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The Vert.x SQL client on the classpath does not keep its pool where this"
                    + " version of the extension looks for it: whether anything waits for " + target
                    + " cannot be watched", e);
        }
    }

    private static Object field(Object owner, Class<?> declaringClass, String name) throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Override
    public void release() {
        if (sampler != null) {
            sampler.stop();
        }
        for (SqlConnection connection : held) {
            try {
                // closing a pooled connection returns it to the pool; wait for that, so the pool is whole again
                // before the test asserts recovery
                connection.close().await();
            } catch (Exception ignored) {
                // the pool takes the connection back regardless; a broken one is destroyed there
            }
        }
        held.clear();
    }

    @Override
    public boolean watched() {
        return sampler != null;
    }

    @Override
    public boolean contended() {
        return sampler != null && sampler.contended();
    }
}
