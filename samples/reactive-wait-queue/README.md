# Reactive wait queue

**The bug.** A reactive SQL client with no `quarkus.datasource.reactive.max-wait-queue-size`. A query that finds
every connection in use joins the pool's wait queue, and the queue has no limit, so the query waits for up to the
connection timeout, which is thirty seconds by default. Nothing says no, so the client learns nothing until its
own deadline passes, and a client that retries puts a second query behind the first. Under load the queue only
grows. A `@QuarkusTest` never has a full pool, so the queue is never used.

**The fix.** A bounded wait queue: `quarkus.datasource.reactive.max-wait-queue-size`. A query that finds no free
connection and no room in the queue fails at once, and the endpoint answers `503 Service Unavailable` with
`Retry-After` while the client is still there to read it.

Start with [`QueriesPileUpTest`](src/test/java/org/acme/catalog/QueriesPileUpTest.java), which takes every
connection and sends a request from a client with a deadline, which passes with no answer, then
[`ExcessQueriesFailFastTest`](src/test/java/org/acme/catalog/ExcessQueriesFailFastTest.java), the same with the
wait queue bounded by a `@TestProfile`.

```bash
./mvnw -pl samples/reactive-wait-queue test
```
