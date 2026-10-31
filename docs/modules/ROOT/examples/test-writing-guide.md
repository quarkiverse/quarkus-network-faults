# Writing tests with Quarkus Network Faults

Quarkus Network Faults puts an in-process proxy in front of every outbound connection the application's configuration describes: REST Clients, datasources, Redis, MongoDB, mailers, GraphQL and gRPC clients, Quarkus LangChain4j models, stores and MCP servers.
A `@QuarkusTest` applies a fault to one of those proxies and asserts what the application does while the dependency fails, and after it recovers.
This guide is everything you need to write such a test.
Read it in order the first time.

## 1. The dependency and the test shape

Add one test dependency; it brings in the extension itself:

```xml
<dependency>
    <groupId>io.quarkiverse.networkfaults</groupId>
    <artifactId>quarkus-network-faults-junit</artifactId>
    <version><!-- the latest release of the extension --></version>
    <scope>test</scope>
</dependency>
```

Every fault test has this shape:

```java
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class OrderResourceTest {

    @Inject
    NetworkFaults faults;

    @Test
    void inventoryServiceHangs() {
        try (var ignored = faults.target(InventoryClient.class).hangs()) {
            // the @Timeout fallback
            when()
                    .post("/orders")
                    .then()
                    .statusCode(503);
        }

        // recovered, breaker reset
        when()
                .post("/orders")
                .then()
                .statusCode(201);
    }
}
```

The imports it needs:

```java
import static io.restassured.RestAssured.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
```

The rules behind the shape:

- `@TestProfile(NetworkFaultsProfile.class)` turns the proxies on. Without it nothing is proxied. If the class already has a profile, make that profile extend `NetworkFaultsProfile`.
- Every fault method but `dropConnections()` returns an `ActiveFault`. Open it in a try-with-resources block: the fault is active inside the block and ended when the block exits, even when an assertion fails.
- Assert the failure behaviour inside the block and the recovery after it. When a fault ends, and after every test, the extension flushes Agroal pools and resets every SmallRye Fault Tolerance circuit breaker, so the call after the block sees a healthy dependency.
- Faults left open are ended after the test.

## 2. Addressing targets

A target is addressed by the CDI bean the application injects to reach the dependency, with the same qualifiers.
A target cannot be selected by its name: names such as `rest-client:inventory` appear only in `faults.targetNames()`, error messages, the Dev UI and the Dev MCP tools.

One line per kind.
The first column is the name `getTargets` shows and the configuration property it lists as `sourceProperty`; `<default>` is the unnamed client or datasource.

| Name and property | What to pass |
|---|---|
| `rest-client:<key>`, `quarkus.rest-client.<key>.url` | `faults.target(InventoryClient.class)`: the interface whose `@RegisterRestClient(configKey = "<key>")` matches, or whose class name or simple name is `<key>` |
| `datasource:<name>:jdbc`, `quarkus.datasource[.<name>].jdbc.url` | `faults.target(DataSource.class)` for the default one, `faults.target(DataSource.class, new DataSourceLiteral("<name>"))` for a named one; `EntityManager.class` or a Quarkus Data repository resolves to the datasource of its persistence unit |
| `datasource:<name>:reactive`, `quarkus.datasource[.<name>].reactive.url` | `faults.target(Pool.class)`, or `Mutiny.SessionFactory.class` |
| `redis:<name>`, `quarkus.redis[.<name>].hosts` | `faults.target(RedisDataSource.class)`, with `RedisClientName.Literal.of("<name>")` for a named one |
| `mongodb:<name>`, `quarkus.mongodb[.<name>].connection-string` or `.hosts` | `faults.target(MongoClient.class)`, with `MongoClientName.Literal.of("<name>")` for a named one |
| `mailer:<name>`, `quarkus.mailer[.<name>].host` / `.port` | `faults.target(Mailer.class)`, with `MailerName.Literal.of("<name>")` for a named one; the mailer needs `mock=false`, or it sends nothing in tests |
| `graphql-client:<key>`, `quarkus.smallrye-graphql-client.<key>.url` | `faults.target(LuckyClient.class)`: the typesafe client interface |
| `grpc-client:<name>`, `quarkus.grpc.clients.<name>.host` / `.port` / `.test-port` | `faults.target(Greeter.class, GrpcClient.Literal.of("<name>"))`; the client needs `test-port` set, or it is not proxied in tests |
| `langchain4j:<provider>:<name>`, `quarkus.langchain4j.<provider>[.<name>].base-url` | `faults.target(ChatModel.class, ModelName.Literal.of("<name>"))`, or the AI service interface that uses the model |
| `langchain4j:chroma:<store>`, `quarkus.langchain4j.chroma[.<store>].url` | `faults.target(EmbeddingStore.class)`, with `EmbeddingStoreName.Literal.of("<store>")` for a named one |
| `mcp:<client>`, `quarkus.langchain4j.mcp.<client>.url` | `faults.target(McpClient.class, McpClientName.Literal.of("<client>"))` |
| `kafka:<host>:<port>`, `kafka.bootstrap.servers` | Do not write fault tests for Kafka: only the bootstrap connection is proxied, the client then connects to the broker directly |

`DataSource` is `javax.sql.DataSource`; `DataSourceLiteral` is `io.quarkus.agroal.DataSource.DataSourceLiteral`, imported by that nested name so the two do not clash.
`faults.all()` is every target at once, for an "everything is down" test.

To find the target of a code path: follow the call from the endpoint under test to the injected client, repository or model it uses, and pass that type.
The Dev MCP resource `quarkus-network-faults_inventory` lists, for each method guarded by a Fault Tolerance annotation, the types its class injects (`injects`); each one is a candidate for `target(...)`.

### Some of the requests of a target

A method reference names the requests one REST Client method sends, from its `@GET`, `@POST` and `@Path`; the client's other methods keep working:

```java
try (ActiveFault f = faults.target(InventoryClient::reserve).hangs()) {
    when()
            .post("/orders?item=a")
            .then()
            .statusCode(503);
}
```

The reference must be `Type::method`; a lambda or `client::reserve` throws `IllegalArgumentException`.

`http().requests(method, path)` names requests on any plaintext HTTP target; the path is matched against the end of the request path, `{id}` matches one segment, and the query string is ignored:

```java
try (ActiveFault f = faults.target(ChatModel.class).http().requests("POST", "/embeddings").http().respondsWith(429)) {
    // only the embedding calls fail
}
```

Both return an `Endpoint`, and need a target reached over plaintext HTTP/1.x (an `http://` or `ws://` address).
An endpoint takes every fault except `unavailable()`, `connectTimeout()` and `dropConnections()`, which act on connections before any request is sent.

## 3. Which fault for which scenario

In the order to reach for them.
The spec is what the Dev MCP `apply` tool and the Dev UI card accept for the same fault.

| Fault | Spec | What the client sees | Exercises | What to assert |
|---|---|---|---|---|
| `unavailable()` | `unavailable` | New connections refused (`ECONNREFUSED`), live connections reset | `@Fallback`; `@CircuitBreaker` across enough calls to reach its `requestVolumeThreshold` and `failureRatio` | The fallback response; once the breaker is open, a later call fails with `CircuitBreakerOpenException` |
| `hangs()` | `hangs` | Connection stays open, nothing comes back: only a timeout ends the call | `@Timeout`, and the client's read timeout | The failure or fallback arrives within a bound around the timeout (see mistakes, response times) |
| `slow(Duration)` | `slow:2s` | Every buffer is delayed by that long | `@Timeout` with the delay above it; tail latency with `every(n)` | As for `hangs()`, or that a call below the timeout still succeeds |
| `http().respondsWith(status)` | `responds-with:503` | An HTTP response with that status; the request never reaches the upstream | `@Fallback`, `@Retry` with `forFirst(n)`, the client's error mapping | The status or exception the application turns it into; plaintext HTTP only |
| `diesMidRequest()`, `diesMidRequest(onNextWrite())` | `dies-mid-request`, `dies-mid-request:next-write` | The connection is reset: an I/O error. With `onNextWrite()` the request never reaches the upstream | `@Retry`, `@Fallback` | The retry count (section 5), or the fallback |
| `closes()`, `closes(moment)` | `closes`, `closes:headers` | The connection is closed cleanly: the request was processed and nothing, or a truncated body, came back | Handling of empty and partial responses and streams | The error the client reports, or the partial data the application kept |

`connectTimeout()` (`connect-timeout`) makes new connection attempts hang until the client's connect timeout; it works on Linux only.
`diesMidRequest`, `closes` and `hangs` take a `Moment` to strike part-way through a response: `Moment.afterBytes(n)`, `Moment.onNextWrite()`, and for plaintext HTTP `Moment.Http.afterHeaders()`, `Moment.Http.afterChunks(n)` and `Moment.Http.afterSseEvents(n)`.

`respondsWith` returns an `HttpResponse` that refines the response with `body(String)`, `contentType(String)`, `header(name, value)` and `bodyOfLength(long)`:

```java
try (ActiveFault f = faults.target(InventoryClient.class)
        .http().respondsWith(429)
        .contentType("application/json")
        .body("{\"error\":\"rate limited\"}")
        .header("Retry-After", "30")) {
    when()
            .post("/orders?item=a")
            .then()
            .statusCode(503);
}
```

The upstream must still be reachable for `respondsWith`: the proxy connects to it before answering.

## 4. Some of the calls

`http().forFirst(n)`, `http().every(n)` and `http().after(n)` select some of the requests an endpoint covers, counted from the moment the fault is applied, across connections, in arrival order.
They return an `Endpoint` that takes any fault:

- `forFirst(n)`: the first `n` fail, then the dependency recovers. For a `@Retry`, `forFirst(2).http().respondsWith(503)` fails two attempts and lets the third succeed.
- `every(n)`: every `n`th, the first included, so `every(2)` selects requests 1, 3, 5. For a retry budget or a breaker's failure ratio.
- `after(n)`: every request after the first `n`. For a dependency that fails once the flow is under way.

A failure that clears, with the REST Client called directly (`inventoryClient` is an injected `@RestClient Instance<InventoryClient>`):

```java
try (ActiveFault f = faults.target(InventoryClient.class).http().forFirst(1).http().respondsWith(503)) {
    WebApplicationException failure = assertThrows(WebApplicationException.class,
            () -> inventoryClient.get().reserve("widget"));
    assertEquals(503, failure.getResponse().getStatus());

    // the next call reaches the real upstream again
    assertEquals("reserved", inventoryClient.get().reserve("widget"));
}
```

One selection per endpoint; a second throws `IllegalStateException`.
These are the only ways to fail some calls and not others, and they are deterministic: the test knows which calls fail because it sends them.

## 5. Observing

`target.traffic()` returns what went through the proxy:

| Method | Use it to |
|---|---|
| `httpRequestsSeen()` | Count requests, including those a fault never relayed: retries made, calls an open breaker skipped |
| `httpRequests()` | Read the last 500 requests, oldest first, as `HttpRequestRecord`s |
| `connections()`, `connectionsSeen()` | Count open and accepted connections; for a database, this is all there is |
| `awaitConnections(n, timeout)` | Wait until a pool has opened `n` connections before applying a fault |
| `awaitHeldConnections(n, timeout)` | Wait until a fault holds `n` connections, under `hangs(onNextWrite())` |

The counters run since the application started: read the counter before the fault and assert on the difference.
Only plaintext HTTP/1.x requests are counted; TLS, HTTP/2 and database protocols are opaque.
An endpoint from a method reference has a `traffic()` too, counting only its requests.

`HttpRequestRecord` holds `time()`, `connectionId()`, `method()`, `target()` (path and query string), `headers()` and `header(name)`, `bodyFingerprint()` (SHA-256 of the body, null without one), `responseTime()` and `responseStatus()` (both null when the request was never answered or is still in flight; a `respondsWith` answer carries its status).

`Attempts` and `Stopwatch` are in `io.quarkiverse.networkfaults.junit`; `onNextWrite` below is a static import of `io.quarkiverse.networkfaults.Moment.onNextWrite`:

```java
Target api = faults.target(InventoryClient.class);
long before = api.traffic().httpRequestsSeen();
try (ActiveFault fault = api.diesMidRequest(onNextWrite())) {
    when()
            .post("/orders/retrying?item=count")
            .then()
            .statusCode(503);
}
// one attempt plus eight retries, from @Retry(maxRetries = 8, delay = 250, jitter = 0)
assertEquals(9, api.traffic().httpRequestsSeen() - before);
Attempts.last(api.traffic().httpRequests(), 9).assertSameBody().assertGapsAtLeast(Duration.ofMillis(200));
```

- `Attempts.last(records, n)` takes the last `n` records; `assertSameBody()`, `assertSameHeader(name)`, `assertGapsAtLeast(duration)` and `assertGapsVary(tolerance)` check the shape of a retry policy, and `gaps()` returns the spacing.
- `Stopwatch.millis(runnable)` times a call, for the lower bound that proves a timeout ended it.
- `diesMidRequest(onNextWrite())` is the fault for counting retries: each attempt is counted and then reset before it reaches the upstream. Under `unavailable()` no HTTP request is sent, so nothing is counted.

## 6. The guard

`NetworkFaultsProfile` registers a guard: if a fault was active on a target and no connection went through that target's proxy while it was, the test fails after it finishes with:

```
Fault had no effect:
  Target [rest-client:inventory]: the 'slow 10ms' fault was active but no connection went
  through the proxy while it was, so it cannot have affected the test. Is the application
  really using quarkus.rest-client.inventory.url?
```

It means the test did not test the fault. The usual causes:

1. The application reads the dependency's address from a configuration key the extension did not rewrite, for example a test resource that sets the URL under the client's class name while `application.properties` uses its config key. The property in the message is the one the proxy was built from; make the application use it.
2. `target(...)` names a bean other than the one the request goes through. Follow the call path again (section 2).
3. The request under test never reached the dependency while the fault was active: the endpoint called does not use it, or the call happened before or after the block.

A fault on some requests is satisfied only by one of those requests, and the message names them: `no POST /inventory/reserve request went through the proxy`.
A second message, `the proxy could not connect to the upstream ...`, means the dependency itself is not running: start it, or fix the test resource that should.
`unavailable()` and `connectTimeout()` are never judged, since refused connections never reach the proxy.

## 7. Timeouts

What a test observes under `hangs()` or `slow()` is decided by whichever timeout fires first, and the library defaults are long:

| Client | Setting | Default |
|---|---|---|
| REST Client | `read-timeout` | 30000 ms |
| Agroal (JDBC pool) | `acquisition-timeout` | 5 s |
| Vert.x clients (reactive SQL, HTTP) | `connectTimeout` | 60000 ms |

Set the timeout the test relies on explicitly, in the test profile, rather than waiting for a library default: a test that waits 30 seconds is slow and asserts nothing about the application's own budget.
A profile that extends `NetworkFaultsProfile`:

```java
public class ShortTimeoutsProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("quarkus.rest-client.inventory.read-timeout", "2000");
        return overrides;
    }
}
```

When the method under test has a `@Timeout`, set the client's timeout longer than it, so the elapsed time tells the two apart.
A `@Timeout` may interrupt the caller without stopping the client call underneath, so a client timeout is needed as well.
A streamed response is not bounded by the read timeout once its headers have arrived; the consumer needs an idle timeout of its own.

## 8. Mistakes to avoid

1. **The fault is on a bean the request does not go through.** The guard fails the test. Fix: follow the call from the endpoint to the injected client and target that type; check its configuration property against `getTargets`.
2. **An HTTP fault on a datasource.** `respondsWith`, the `Moment.Http` moments and faults on some requests throw `UnsupportedOperationException` on a target that is not plaintext HTTP: a datasource, Redis, MongoDB, or an `https://` client. Fix: use `unavailable()`, `hangs()`, `slow(...)`, `diesMidRequest(...)` or `closes(...)` there. `getTargets` shows `plaintextHttp` for each target.
3. **A second fault on a target that has one.** It throws `IllegalStateException` naming the active fault. Fix: close the first block before opening the next, or put the faults on different requests with method references, which may be active together.
4. **Asserting a response time without slack.** Exact durations fail on a slow build machine. Fix: assert a lower bound a little below the timeout, which proves the timeout ended the call, and an upper bound with generous slack that stays below the next timeout that could fire, for example `ms >= 900 && ms < 1_900` for a one-second `@Timeout` with a two-second read timeout.
5. **Forgetting that pools keep connections.** `connectTimeout()` acts on new connection attempts only, and a pool reuses the connection an earlier request opened, so the call succeeds. Fix: call `faults.target(...).dropConnections()` before the fault when the test needs a new connection. `hangs()`, `slow(...)` and the other faults act on open connections too.
6. **A flaky test from a random or timed fault.** Do not sleep for a guessed duration or fail calls by chance. Fix: select the calls with `forFirst(n)`, `every(n)` or `after(n)`, and wait for an observable condition, such as `httpRequestsSeen()` reaching a count, instead of sleeping.
7. **Asserting a status you did not observe.** The status a fault produces depends on the application's fallbacks, exception mappers and timeouts, and is often not the one you would guess. Fix: observe it first, by running the test once and reading the failure, or by applying the same fault in dev mode with the agent playbook; then assert what was observed, inside the block and after it. If the observed behaviour looks wrong, report it to the developer instead of asserting it.
