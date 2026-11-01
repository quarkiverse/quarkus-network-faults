# Resiliency Testing Dev UI demo

A small orders service to try the Resiliency Testing Dev UI card and the Dev MCP tools by hand. It needs
Docker for the PostgreSQL Dev Service, and the extension installed in the local Maven repository
(`./mvnw install` at the root of this repository).

The application takes orders. Placing one calls an inventory service through a REST Client and then
inserts a row. The inventory service is served by the application itself, so nothing else has to
run, but the REST Client reaches it through the Resiliency Testing proxy exactly as it would reach a
real service. `InventoryService` carries the resilience policy under test: a `@Timeout` with a
`@Fallback` and a `@CircuitBreaker` on `reserve`, a `@Retry` on `reserveWithRetry`.

## Run it

From the root of the repository:

```bash
./mvnw -f samples/dev-ui-demo/pom.xml quarkus:dev
```

Then open the Dev UI at <http://localhost:8080/q/dev-ui> and find the **Resiliency Testing** card. It
lists two targets: `rest-client:inventory` and `datasource:<default>:jdbc`.

## Things to try in the card

Drive the application from a second terminal while you apply faults:

```bash
curl -i -X POST 'localhost:8080/orders?item=apple'        # 201 while the inventory is healthy
curl -i -X POST 'localhost:8080/orders/retrying?item=pear' # the @Retry path
curl -i 'localhost:8080/orders'                           # the datasource alone
curl -N 'localhost:8080/orders/stock-updates'             # six Server-Sent Events, 300 ms apart
```

- Apply `hangs` to `rest-client:inventory`, then place an order: it answers `503` after the
  two-second `@Timeout`, and the row's connection shows `held: true`. Place two more and the
  circuit breaker opens: the answer is immediate and no connection reaches the proxy.
- Apply `responds-with:503` with a body such as `{"error":"out of stock"}` and content type
  `application/json`: the inventory "fails" with a status of your choosing, and the Requests view
  shows the status each request came back with.
- Put `POST /inventory/reserve` in the scope field before applying a fault: only the reservation
  calls are affected, and the stock-updates stream keeps working.
- Apply `dies-mid-request:sse:3` or `hangs:sse:3`, then `curl -N` the stream: it is cut, or
  stalls, after the third event.
- Apply `unavailable` to `datasource:<default>:jdbc`, then list the orders.
- After a fault ends, the target's **Fault** column says what it did: how many connections and
  requests went through it, or *no traffic* for a fault on the wrong target, or *not judged* for
  `unavailable`. **Export report** in the toolbar renders the whole session as Markdown.

## Drive it from an AI agent

The application enables the Dev MCP server at `http://localhost:8080/q/dev-mcp`. With Claude Code,
for example:

```bash
claude mcp add --transport http resiliency-testing http://localhost:8080/q/dev-mcp
```

The read-only tools and `resetAll` are enabled by default. The tools that apply or end a fault,
`apply`, `reset` and `dropConnections`, are opt-in: enable them in the Dev UI under *Settings*
(the gear icon), *Dev MCP*, *Tools*. Three resources come with the tools: the test-writing guide,
the agent playbook, and the inventory of this application's guarded methods.

Prompts that show what the tools are for:

- "Read the quarkus-resiliency-testing test-writing guide and the inventory, then write the
  `@QuarkusTest`s that verify what `/orders` does when the inventory service hangs, fails, or
  dies mid-response."
- "Run the playbook against `rest-client:inventory`: form a hypothesis for each annotation on
  `InventoryService`, apply the fault, call the endpoint, and tell me whether the policy held."

## The test side

`src/test/java/org/acme/orders/OrderResourceTest.java` pins two of the scenarios above as tests,
the way an agent would write them from the guide:

```bash
./mvnw -f samples/dev-ui-demo/pom.xml test
```
