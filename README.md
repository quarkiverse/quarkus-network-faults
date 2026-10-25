# Quarkus Network Faults

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.networkfaults/quarkus-network-faults?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.networkfaults/quarkus-network-faults)
[![Documentation](https://img.shields.io/badge/docs-quarkiverse-4695EB?logo=quarkus&style=flat-square)](https://docs.quarkiverse.io/quarkus-network-faults/dev/index.html)
[![Quarkus](https://img.shields.io/badge/Quarkus-4.0.0.Beta1%2B-4695EB?logo=quarkus&style=flat-square)](https://quarkus.io/)

**Make a dependency fail on purpose, inside your `@QuarkusTest`.**

Every outbound connection your application configures gets an in-process proxy in front of it.
A test applies a fault to that proxy, and the real client library, the REST Client, the JDBC
pool, the Redis or Kafka client, fails the way it fails in production: refused, hung, slow,
reset mid-response, answered with a 503. No Docker, no sidecar, no second environment. The
resilience of a service is tested where it lives, in its own test suite.

## The test

```java
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class OrderResourceTest {

    @Inject
    NetworkFaults faults;

    @Test
    void inventoryServiceHangs() {
        try (var ignored = faults.target(InventoryClient.class).hangs()) {
            // the @Timeout fallback answers
            when()
                    .post("/orders")
                    .then()
                    .statusCode(503);
        }

        // the fault is over and the circuit breaker was reset: the next order goes through
        when()
                .post("/orders")
                .then()
                .statusCode(201);
    }
}
```

That is the whole test. The target is the bean the application already injects, the fault is
one method call, and the try-with-resources block is the window during which the dependency
is broken. When the block ends the proxy relays again, connection pools are flushed and circuit
breakers are reset, so the recovery can be asserted in the same test.

One test dependency brings everything in:

```xml
<dependency>
    <groupId>io.quarkiverse.networkfaults</groupId>
    <artifactId>quarkus-network-faults-junit</artifactId>
    <version>${quarkus-network-faults.version}</version>
    <scope>test</scope>
</dependency>
```

Support for each kind of dependency is picked up from the extensions already in the
application; there is nothing else to add.

### More scenarios, same shape

A transient failure the `@Retry` absorbs: the first two calls get a 503, the third reaches the
service.

```java
try (var ignored = faults.target(InventoryClient.class).http().forFirst(2).http().respondsWith(503)) {
    when()
            .post("/orders/retrying")
            .then()
            .statusCode(201);
}
```

A streamed response cut after the third Server-Sent Event: the service relays three events,
then ends its own stream cleanly instead of cutting the caller's.

```java
try (var ignored = faults.target(InventoryClient.class).diesMidRequest(Moment.Http.afterSseEvents(3))) {
    when()
            .get("/orders/stock-updates")
            .then()
            .statusCode(200)
            .body(containsString("stock level 4"))
            .body(containsString("stream ended early"))
            .body(not(containsString("stock level 3")));
}
```

A database that stops answering: the request fails on the pool's timeout and the caller gets a
503, not a stack trace.

```java
try (var ignored = faults.target(DataSource.class).hangs()) {
    when()
            .get("/orders")
            .then()
            .statusCode(503);
}
```

A test that applies a fault no request went through fails, with a message naming the
configuration property to look at. A fault test cannot pass by accident.

## See it before you test it

In dev mode the **Network faults** card in the Dev UI lists every target of the running
application and lets you break them by hand.

![The Network faults Dev UI card applying a fault, the Requests view of a target and the exported report](docs/modules/ROOT/assets/images/dev-ui-demo.gif)

- **Every fault, on any target**, or only on the requests a `POST /path` scope selects. A
  `responds-with:503` can carry a body and a content type.
- **Requests**: every HTTP request a target sent, with its headers, body fingerprint, status
  and response time, so a retry storm or an open circuit breaker is visible at a glance.
- **The verdict on the last fault** of every target: whether any traffic went through it while
  it was active, which is the guard's verdict in dev mode, so a fault on the wrong target shows.
- **Report**: the whole session as Markdown, to paste into an issue or a pull request.

Try it on the sample application, which needs only Docker:

```bash
./mvnw -f samples/dev-ui-demo/pom.xml quarkus:dev
```

## Let an agent write the tests

The dependency failure tests a service needs are predictable: one per guarded method, one per
failure mode. That is work an AI agent can do, if it knows how. The extension gives it what it
needs.

With `quarkus.dev-mcp.enabled=true`, the Dev UI card becomes a set of **Dev MCP tools** on the
running application, with descriptions written for an agent. Point your agent at
`http://localhost:8080/q/dev-mcp`; with Claude Code:

```bash
claude mcp add --transport http network-faults http://localhost:8080/q/dev-mcp
```

Three resources come with the tools:

- **The test-writing guide**: the one document an agent needs to write a test with this
  extension. It is the same text on the documentation site, for an agent working in a repository
  without the application running.
- **The inventory**: every method of the application guarded by a Fault Tolerance annotation,
  with the effective values, and the injected types a test can pass to `target(...)`. It turns
  "write resilience tests for this service" into a list.
- **The playbook**: safety rules and an experiment loop. Form a hypothesis, apply the fault,
  call the endpoint, read what happened, and then write the test that pins it.

A prompt that uses all three:

> Read the quarkus-network-faults test-writing guide and the inventory, then write the
> `@QuarkusTest`s that verify what `/orders` does when the inventory service hangs, fails, or
> dies mid-response. Run the experiments first if you are unsure what to assert.

The tools that apply faults are opt-in, enabled from the Dev UI, so an agent cannot break a
running application until you say so. The extension generates no test code: the agent writes
it, from what it read and what it observed. See
[AI Agents](https://docs.quarkiverse.io/quarkus-network-faults/dev/agents.html).

## Why it works

- **Nothing to start.** The proxy is a Vert.x server inside the test JVM. Dev Services work
  unchanged, and so does every client library, because nothing about it is mocked.
- **Targets are the beans you inject.** `faults.target(InventoryClient.class)`,
  `faults.target(DataSource.class)`, with the same qualifiers the application uses.
- **Faults the network really produces.** Refused connections, connect timeouts, hangs, latency,
  resets and clean closes at a chosen moment of the response, and HTTP error statuses. A fault
  can strike the first n requests, every nth, or one method of a client, deterministically.
- **Consequences are undone.** When a fault ends, and after every test, Agroal pools are
  flushed and SmallRye Fault Tolerance circuit breakers are reset.

## How it compares

Other tools make dependencies fail too; they do it at a different place, and that decides what
a test can prove.

| | Where the fault happens | What it can produce | Writing a test | When a test is wrong | Dev UI and agents |
|---|---|---|---|---|---|
| **Quarkus Network Faults** | On the wire, in a proxy inside the test JVM, between the real client library and the real dependency | Refused and dropped connections, hangs, latency, resets and clean closes at a chosen moment of the response, synthetic HTTP statuses, on all or some requests; the client's timeouts, retries and breakers react as in production | **You write the test, nothing else.** One test dependency; URLs are rewritten to the proxies for you; the dependency is the bean you inject; a fault is one statement in a try-with-resources; deterministic with `forFirst`, `every`, `after`; faults ended, pools flushed and breakers reset after each test | **The test tells you.** A fault no request went through fails the test and names the configuration property to check; a second fault on a busy target, or an HTTP fault on a datasource, is refused with a message saying what to do instead | A card that applies every fault, shows each target's requests and the verdict on its last fault, and exports a report; Dev MCP tools, a test-writing guide, a playbook and an inventory of the guarded methods, so an agent can experiment and then write the tests |
| [Quarkus Goblin](https://github.com/quarkiverse/quarkus-goblin) | Inside the application, in JAX-RS filters, CDI interceptors and an Agroal hook | Latency, exceptions and HTTP statuses thrown from Java code on the application's own endpoints, beans and connection acquisition; rewritten response bodies and headers | **Made for clicking, not for tests.** Chaos is switched on through configuration or `AssaultEngine.setActive(true)` and acts globally, by package, annotation and a percentage of requests, so deterministic at 100 percent only | **Silence.** An assault that never fired leaves the test green; the history shows it, the test does not | The richest dashboard of the four: profiles, history, a Markdown report, metrics and traces; Dev MCP tools, a playbook and an inventory for an agent that runs experiments, with no test API to write them down in |
| [Toxiproxy](https://github.com/Shopify/toxiproxy) via [Testcontainers](https://java.testcontainers.org/modules/toxiproxy/) | On the wire, in a container between the application and a dependency that is also reachable from that container | Latency, bandwidth, slow close, timeout, reset and data limits on a TCP stream, for any language | **The plumbing is yours.** Docker on every machine and runner; a test resource that starts a network, the dependency on it and the proxy container; a client for the API; the application's client reconfigured to a mapped port; a toxic created and removed around each test; no per-request selection | **Silence.** A toxic on a proxy nothing talks to is invisible, and a pooled connection opened before it keeps working | A CLI and an HTTP API against the container; nothing in the Quarkus Dev UI, nothing for an agent |
| [WireMock](https://wiremock.org/) | In place of the dependency: a stub server answers instead of it | Scripted HTTP responses, delays, connection resets and malformed bodies | **A stub per call.** A stub server from a test resource or the `quarkus-wiremock` Dev Service, the client reconfigured to its URL, and a stub for every request the test makes, so the real dependency is never exercised | **If you ask.** An unstubbed request gets a 404, and `verify(...)` checks a call was made, when the test includes it | An admin API, and a Dev Service that starts the server; nothing for an agent |

The same hang, written with Toxiproxy, has to build its own world before the test can say what
it is about: a network, the dependency on it, the proxy container, a proxy over the API, the
client's URL pointed at a mapped port, and the toxic added and removed by hand.

```java
ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.11.0").withNetwork(network);
ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
Proxy inventory = client.createProxy("inventory", "0.0.0.0:8666", "inventory:8080");
config.put("quarkus.rest-client.inventory.url", "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666));

inventory.toxics().timeout("hang", ToxicDirection.DOWNSTREAM, 0);
try {
    when().post("/orders").then().statusCode(503);
} finally {
    inventory.toxics().get("hang").remove();
}
```

The difference is not the line count. Toxiproxy and WireMock are general tools that know nothing
about the application in front of them, so everything that connects them to it is yours to write
and to keep right. This extension is part of the Quarkus application, and that is what makes it
the natural fit for building a resilient one:

- **It knows the configuration.** Every REST Client, datasource, Redis, MongoDB, Kafka or
  LangChain4j connection the application configures is a target, Dev Services included, with
  nothing to point anywhere.
- **It knows the beans.** A test names a dependency the way the application does, by the
  type it injects, so the test reads like the code it verifies.
- **It knows the resilience layer.** Pools are flushed and SmallRye Fault Tolerance circuit
  breakers are reset when a fault ends, so every test starts clean and the recovery can be
  asserted in the same method.
- **It knows what the fault should have met.** A fault no request went through fails the test
  and names the configuration property to look at, so a wrong target is a red test, not a green
  one that proved nothing.
- **It knows the protocol.** On plaintext HTTP it can fail one request in n, answer with a
  chosen status, cut a stream after the third event and count every attempt a retry made.
- **It lives in dev mode too.** The same faults from the Dev UI card, the same verdict, and
  Dev MCP tools with a test-writing guide, so an agent can experiment against the running
  application and write the test that pins what it saw.

Goblin is a Quarkus extension as well, and a good one for exploring, but it faults the
application's own code with exceptions thrown from inside it. A dependency does not fail that
way: it refuses, hangs, slows down or stops answering half-way, and the client library's own
timeouts and retries decide what the application sees. That is what this extension produces,
and what a resilient application has to be tested against.

## What it covers

Targets are discovered from the application's configuration, Dev Services included, and a test
addresses one by the bean the application injects.

| Dependency | How a test addresses it |
|---|---|
| JDBC datasource | `target(DataSource.class)`, a named one with its `@DataSource` literal |
| Reactive datasource | `target(Pool.class)` |
| Hibernate ORM, Hibernate Reactive, Quarkus Data, Jakarta Data | `target(EntityManager.class)`, `target(Mutiny.SessionFactory.class)` or the repository type, resolved to the datasource behind it |
| REST Client | `target(InventoryClient.class)`, the interface; `target(InventoryClient::reserve)` for one method's requests |
| Redis | `target(RedisDataSource.class)`, or the Vert.x clients; standalone mode |
| MongoDB | `target(MongoClient.class)` or the reactive client; standalone servers |
| Mailer | `target(Mailer.class)`, a named one with its `@MailerName` literal; with `quarkus.mailer.mock=false` |
| SmallRye GraphQL client | the typesafe interface, or a dynamic client by name |
| gRPC client | `target(Greeter.class, GrpcClient.Literal.of("greeter"))` |
| Kafka | the emitter or client bean |
| Quarkus LangChain4j | `target(ChatModel.class, ModelName.Literal.of("name"))` or the AI service; chat memory and embedding stores through the client behind them, Chroma by URL; MCP servers with `target(McpClient.class, McpClientName.Literal.of("name"))` |

The faults: `unavailable`, `connectTimeout`, `hangs`, `slow`, `diesMidRequest`, `closes`,
`dropConnections`, `http().respondsWith(status)` with a body and headers, and latency with
jitter, bandwidth and slicing under `advanced()`. `diesMidRequest`, `closes` and `hangs` take a
moment: the client's next write, or in the response after n bytes, after the headers, after n
chunks or after n Server-Sent Events. `http().forFirst(n)`, `every(n)` and `after(n)` select
some of the requests, and `traffic()` counts and lists what went through, so a test can check
that every retry carried the same idempotency key.

Read the [documentation](https://docs.quarkiverse.io/quarkus-network-faults/dev/index.html) for
the quick start, the guides, and the reference pages for faults, targets, the guard and the
client timeout defaults these tests tend to surface.

## Requirements

- Quarkus `4.0.0.Beta1` or later
- Java 21 or later

## Known limitations

- `connectTimeout()` is Linux only.
- TLS passes through but certificates must accept `127.0.0.1`; request counting and the HTTP
  faults need plaintext HTTP/1.x.
- No `@QuarkusIntegrationTest` support.
- Faults that fire at random or on a clock were left out on purpose: a test that fails one run
  in twenty is worse than no test. `forFirst`, `every` and `after` give the same scenarios a
  deterministic shape.

## Build

`./mvnw install`. Tests need Docker for Dev Services.
