# Unbounded worker queue

**The bug.** No `quarkus.thread-pool.queue-size`. A blocking request that finds every worker thread busy is queued,
and the queue has no limit, so it waits for as long as it takes. The server never says no, so the client learns
nothing until its own deadline passes, and a client that retries puts a second request behind the first. Under
load the queue only grows, and most of what it holds is for clients that have given up. A `@QuarkusTest` always
has a free worker, so the queue is never used.

**The fix.** A bounded queue: `quarkus.thread-pool.queue-size`. A request that finds no free worker and no room in
the queue is rejected, and Quarkus answers it `503 Service Unavailable` at once, while the client is still there to
read it. The client can try another instance or come back later.

Start with [`RequestsWaitForeverTest`](src/test/java/org/acme/reports/RequestsWaitForeverTest.java), which
occupies every worker and sends a request from a client with a deadline, which passes with no answer, then
[`ExcessIsRejectedTest`](src/test/java/org/acme/reports/ExcessIsRejectedTest.java), the same with a queue size
set by a `@TestProfile`.

```bash
./mvnw -pl samples/unbounded-worker-queue test
```
