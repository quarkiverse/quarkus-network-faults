package io.quarkiverse.networkfaults;

import java.lang.annotation.Annotation;
import java.util.Set;

import jakarta.enterprise.inject.Instance;

/**
 * Entry point for injecting network faults into the outbound connections of the application under test.
 * <p>
 * Inject it into a test class that uses {@code @TestProfile(NetworkFaultsProfile.class)}:
 *
 * <pre>
 * &#64;Inject
 * NetworkFaults faults;
 *
 * &#64;Test
 * void inventoryHangs() {
 *     try (var ignored = faults.target(InventoryClient.class).hangs()) {
 *         when().post("/orders").then().statusCode(503);
 *     }
 * }
 * </pre>
 *
 * A target is addressed by the CDI bean the application uses to reach it: a type and qualifiers
 * ({@code target(DataSource.class)}, {@code target(DataSource.class, new DataSource.DataSourceLiteral("users"))},
 * {@code target(RedisDataSource.class, RedisClientName.Literal.of("cache"))}) or an injected {@code Instance}. The
 * extension owning the bean maps it to the connection; a REST Client interface needs no qualifier. A target cannot be
 * selected by its registry name, such as {@code datasource:<default>:jdbc}; the names show up in
 * {@link #targetNames()}, in error messages and in the Dev UI.
 * <p>
 * Overload needs no profile: {@code pool(DataSource.class).exhausted()} on a datasource or reactive pool,
 * {@link #workerPool()} and {@link #eventLoops()} work in any {@code @QuarkusTest}. A pool is addressed like a
 * target, by the bean the application uses it through, and a bean with a pool behind a connection, such as a
 * datasource, is both.
 * <p>
 * The requests one method of a bean sends are addressed by a method reference, {@code target(InventoryClient::reserve)},
 * and make an {@link Endpoint}: faults on it leave the rest of the target alone.
 */
public interface NetworkFaults {

    /**
     * The build-time property that turns fault injection on; {@code NetworkFaultsProfile} sets it.
     */
    String ENABLED_PROPERTY = "quarkus.network-faults.enabled";

    /**
     * The target behind the bean of this type and qualifiers, as the application would inject it.
     *
     * @throws IllegalArgumentException if no extension recognises the bean, or nothing matches
     */
    Target target(Class<?> type, Annotation... qualifiers);

    /**
     * The target behind the bean an {@code Instance} resolves to, for example an injected
     * {@code @RestClient Instance<InventoryClient>}.
     *
     * @throws IllegalArgumentException if no extension recognises the bean, or nothing matches
     */
    Target target(Instance<?> instance);

    /**
     * The requests a call to one method of a bean sends, named by a method reference: {@code target(InventoryClient::reserve)}
     * for a REST Client method, whose HTTP method and path come from its {@code @POST} and {@code @Path}. Faults
     * applied to the endpoint act on those requests only; the other methods of the client keep working.
     *
     * @throws IllegalArgumentException if the argument is not a method reference of the form {@code Type::method}, or
     *         no extension recognises the method as one that sends requests
     */
    <B> Endpoint target(MethodReference.Method0<B> method);

    /**
     * Like {@link #target(MethodReference.Method0)}, for a method with one parameter.
     */
    <B, P1> Endpoint target(MethodReference.Method1<B, P1> method);

    /**
     * Like {@link #target(MethodReference.Method0)}, for a method with two parameters.
     */
    <B, P1, P2> Endpoint target(MethodReference.Method2<B, P1, P2> method);

    /**
     * Like {@link #target(MethodReference.Method0)}, for a method with three parameters.
     */
    <B, P1, P2, P3> Endpoint target(MethodReference.Method3<B, P1, P2, P3> method);

    /**
     * Like {@link #target(MethodReference.Method0)}, for a method with four parameters.
     */
    <B, P1, P2, P3, P4> Endpoint target(MethodReference.Method4<B, P1, P2, P3, P4> method);

    /**
     * Like {@link #target(MethodReference.Method0)}, for a method with five parameters.
     */
    <B, P1, P2, P3, P4, P5> Endpoint target(MethodReference.Method5<B, P1, P2, P3, P4, P5> method);

    /**
     * The pool behind the bean of this type and qualifiers, as the application would inject it: the Agroal pool of a
     * {@code DataSource}, the connection pool of a reactive SQL client {@code Pool}. It needs no
     * {@code NetworkFaultsProfile}.
     *
     * @throws IllegalArgumentException if no extension recognises the bean, or it has no pool to exhaust
     */
    Exhaustible pool(Class<?> type, Annotation... qualifiers);

    /**
     * The pool behind the bean an {@code Instance} resolves to, for example an injected
     * {@code @DataSource("audit") Instance<DataSource>}.
     *
     * @throws IllegalArgumentException if no extension recognises the bean, or it has no pool to exhaust
     */
    Exhaustible pool(Instance<?> instance);

    /**
     * The worker pool Quarkus dispatches blocking requests to, named {@code worker-pool}. Exhausting it takes every
     * thread, each busy with a task that waits for the fault to end, so a blocking request made meanwhile queues
     * behind them, or is rejected when the queue is bounded, as it is when real load has taken every worker. The event
     * loop is not involved, so non-blocking endpoints still answer. It needs no {@code NetworkFaultsProfile}.
     * <p>
     * This fault has no guard. The pool's queue cannot be observed: its statistics are off unless
     * {@code jboss.threads.eqe.statistics} is set, and an unbounded queue, the default, reports no size at all. A
     * blocking request made during the window meets the condition by definition, and a non-blocking one not meeting
     * it is the behaviour such a test asserts.
     */
    Exhaustible workerPool();

    /**
     * The event loops of the application, which a test can watch for blocking. It needs Vert.x in the application and
     * no {@code NetworkFaultsProfile}. Unlike a fault, a watch changes nothing: it is not a network fault, takes no
     * target, and runs no cleaners when it ends.
     *
     * @throws IllegalStateException if the application has no Vert.x
     */
    EventLoops eventLoops();

    /**
     * Every known target at once, for "everything is down" tests.
     */
    Target all();

    /**
     * Names of every target discovered so far. Targets appear when their configuration is first read.
     */
    Set<String> targetNames();

    /**
     * Ends every active fault and cleans up its consequences (pools, circuit breakers).
     */
    void resetAll();
}
