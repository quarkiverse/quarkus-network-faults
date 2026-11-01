# Resiliency Testing agent playbook

## What this is

Quarkus Resiliency Testing puts a proxy in front of every outbound connection of the application: REST Clients, datasources, Redis, MongoDB, mailers, GraphQL and gRPC clients, Quarkus LangChain4j models and MCP servers.
In dev mode, the Dev MCP tools below apply faults to those proxies in the running application, so you can make a dependency fail on purpose and watch what the application does.

An experiment finds out what the application really does when one dependency fails in one way.
Its purpose is a test: what you observe is what the test asserts, so every experiment ends with a test written with the Dev MCP resource `quarkus-resiliency-testing_testWritingGuide`.

## The tools

Over Dev MCP every tool carries the prefix `quarkus-resiliency-testing_`.

| Tool | Enabled | What it does |
|---|---|---|
| `quarkus-resiliency-testing_getStatus` | by default | Whether fault injection is on, how many faults are active, every target. Call it first |
| `quarkus-resiliency-testing_getTargets` | by default | Every target: `name`, `kind`, `sourceProperty`, `upstream`, `plaintextHttp`, `fault`, counters, open connections |
| `quarkus-resiliency-testing_getFaults` | by default | Every fault spec `apply` accepts, with what the application sees |
| `quarkus-resiliency-testing_getEvents` | by default | The event log of a target, oldest first |
| `quarkus-resiliency-testing_getRequests` | by default | The HTTP requests a target saw, with `responseStatus` and `responseTimeMillis`; plaintext HTTP targets only |
| `quarkus-resiliency-testing_getHistory` | by default | Every fault applied since the application started, newest first, with the guard's `verdict` |
| `quarkus-resiliency-testing_getReport` | by default | A Markdown report of targets, faults and counters |
| `quarkus-resiliency-testing_resetAll` | by default | Ends every fault on every target |
| `quarkus-resiliency-testing_apply` | opt-in | `apply(target, fault, requests, body, contentType, headers)`: applies one fault |
| `quarkus-resiliency-testing_reset` | opt-in | Ends every fault on one target |
| `quarkus-resiliency-testing_dropConnections` | opt-in | Closes the open connections of a target once; no fault stays active |

`target` is a name from `getTargets`, such as `rest-client:inventory`; a prefix such as `rest-client` covers every target under it.
`fault` is a spec such as `unavailable`, `hangs`, `slow:2s`, `dies-mid-request:next-write` or `responds-with:503`.
`requests`, `body`, `contentType` and `headers` are optional and may be sent empty: `requests` is `"METHOD /path"`, such as `"POST /inventory/reserve"`, to fault only those requests; `body`, `contentType` and `headers` refine `responds-with`, `headers` as one `Name: value` per line, such as `Retry-After: 30`.

If `getStatus` returns `enabled: false`, fault injection is off: ask the developer to add `%dev.quarkus.resiliency-testing.enabled=true` to `application.properties`.

## Safety rules

1. **One fault per whole target at a time.** The API enforces it. `apply` without `requests` ends the target's faults before applying the new one, so you replace a fault rather than stack one. Faults on different requests of one target may be active together.
2. **End every fault when you are done.** `resetAll` is the way: it is always enabled and safe to call at any time. Never leave the developer's application with a fault active.
3. **Do not work around an opt-in tool.** If `apply`, `reset` or `dropConnections` is not available, ask the developer to enable it in the Dev UI, under Settings, Dev MCP tab, Tools. Do not edit configuration, call the JSON-RPC endpoint directly or change application code to get the same effect.
4. **Read `getEvents` before it rolls.** Each target keeps its 500 most recent events. Read the log right after the call you want to explain.
5. **A fault no request exercised proves nothing.** If no connection or covered request went through the target while the fault was active, the observation says nothing about that fault. Check before concluding (see "Was the fault exercised?").

## Which fault proves which annotation

| Annotation | Fault spec | What proves it held |
|---|---|---|
| `@Timeout` | `hangs`, or `slow:<d>` with `d` above the timeout | The call fails or falls back after about the timeout, not after the client's read timeout |
| `@Fallback` | `unavailable`, `responds-with:500`, `hangs` | The fallback's response; try more than one, since an HTTP error and a timeout reach the fallback through different exceptions |
| `@CircuitBreaker` | `unavailable`, over enough calls to reach `requestVolumeThreshold` and `failureRatio` | After enough failed calls, later calls fail at once with `CircuitBreakerOpenException`, or the fallback, without waiting on the dependency |
| `@Retry` | `dies-mid-request:next-write`, or `responds-with:503` | `getRequests` shows one request per attempt: `maxRetries + 1` while the fault lasts |
| No annotation | `hangs` | How long the call waits is the client's own timeout; often 30 seconds or more |

## Where to start

1. Read the resource `quarkus-resiliency-testing_inventory`. Its `guardedMethods` list every method guarded by a Fault Tolerance annotation: `class`, `method`, `annotations` with their effective values, and `injects`, the types the class injects that a test can pass to `target(...)`. Read its `notes`: values overridden in configuration are not reflected.
2. Call `getTargets`. Join each injected type to a target through the target's `sourceProperty`: a REST Client interface with `@RegisterRestClient(configKey = "inventory")` is the target whose property is `quarkus.rest-client.inventory.url`, named `rest-client:inventory`; a `javax.sql.DataSource` is `datasource:<default>:jdbc`, a named one `datasource:<name>:jdbc`.
3. Find the endpoint that reaches the guarded method, by reading the resource classes. You will call it in every experiment.

Each guarded method, injected target and fault from the table above is one experiment, and one test.

## The loop

For each experiment:

1. **Hypothesis.** One sentence: "When `rest-client:inventory` hangs, `POST /orders` answers 503 within about one second, because `InventoryService.reserve` has `@Timeout(1000)` and a fallback."
2. **Fault.** `apply` one fault to one target. Nothing else changes during the experiment.
3. **Observation.** Call the application's endpoint yourself, over HTTP. Record the status, the body and the elapsed time.
4. **Confirmation.** Check that the fault was exercised, as the next section describes: while it is active, `getEvents` and `getTargets` (and `getRequests` for a plaintext HTTP target); once it has ended, `verdict` in `getHistory`, which settles it. If it was not exercised, the experiment is inconclusive.
5. **Conclusion.** Held, did not hold, or inconclusive, against the hypothesis.
6. **End the fault.** `reset` the target, or `resetAll`. Call the endpoint again and record the recovery status.
7. **Then write the test**, following `quarkus-resiliency-testing_testWritingGuide`: the same target, through the bean the application injects; the same fault, as the Java method matching the spec; the status you observed inside the fault block, and the recovery status after it. Assert what you observed, not what you expected.

If the conclusion is "did not hold", the test you write documents what the application does now; tell the developer, and let them decide whether the behaviour or the test should change.

## Was the fault exercised?

Once the fault has ended, `getHistory` settles the question: its `verdict` is the test guard's own, `saw-traffic` or `no-traffic`. The `lastFault` of each target in `getTargets` carries the same verdict for the fault that ended last.
`true` means a connection, or a request the fault was scoped to, went through the proxy while the fault was active; `false` means the fault cannot have affected the application, and the experiment is inconclusive.
It is `null` while the fault is active.

While the fault is active, the evidence depends on the fault and the target:

- `getEvents` shows `FAULT_STARTED`, then the connections the call opened. A pool may reuse a connection opened before the fault, so a warm pool shows no new `CONNECTION_OPENED` and no change in `connectionsSeen`; that alone does not mean the fault was missed.
- Under `hangs`, a connection the fault is holding shows `held: true` in the `connections` of `getTargets`. That is the evidence: the request is held before the proxy reads it, so it does not appear in `getRequests` until the fault ends.
- For a target with `plaintextHttp: true` in `getTargets`, `getRequests` lists the requests that went through, with their `responseStatus`. For any other target, such as a datasource, `getRequests` is always empty and says nothing about the fault; use `getEvents`, `getTargets` and the `verdict`.

Signs it was not exercised:

- `verdict: no-traffic` in `getHistory` once the fault ended.
- `upstreamConnectFailures` rises in `getTargets`, and `getEvents` shows `UPSTREAM_CONNECT_FAILED`.

The two explanations, which are the same ones the test guard gives:

1. **The application does not use that address.** It reached the dependency through a configuration key other than `sourceProperty`, or the endpoint you called goes through another bean. Check the property and the call path, then repeat.
2. **The dependency is not running.** The proxy could not connect to the upstream, so the application's connections were reset whatever the fault. Ask the developer to start it.

`unavailable` and `connect-timeout` cannot be judged this way: refused and dropped connections never reach the proxy, so their `verdict` is `not-judged` and their counts mean nothing.
For those two, the observed behaviour of the application is the evidence.

## The report

When the experiments are done, call `getReport` and give the developer its Markdown together with a write-up:

- Each hypothesis, in the order you tested them.
- What you observed: status, body and elapsed time, under the fault and after it.
- The verdict: held, did not hold, or inconclusive, and why.
- The tests you wrote, by class and method, and the faults they apply.

Then `resetAll`.

## The application's promises

The extension knows the code: annotations, targets, timeouts.
It cannot know what the application promises: what "healthy" means, which dependency may fail open, what must never be touched.
Look for `RESILIENCE.md` in the project root and test against the promises it states.
If it is missing, ask the developer for those promises before you start, and offer to write them down in a `RESILIENCE.md` with them.
