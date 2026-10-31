# Blocking status endpoint

**The bug.** A status endpoint, polled by the load balancer or an uptime monitor, that returns a constant. It does
no I/O, but a plain JAX-RS method is dispatched to a worker thread, so it needs a free worker like any other
request. When the workers are saturated, it is unavailable along with the real work, the balancer takes the
instance out, and the monitor pages for an instance that is busy, not broken. In a `@QuarkusTest` a worker is
always free, so the endpoint always answers.

**The fix.** `@NonBlocking`. The endpoint runs on the event loop, which the worker pool's saturation does not touch,
so it keeps answering, and it says what is true: the instance is up and its workers are full.

Start with [`StatusGoesDownWithTheWorkersTest`](src/test/java/org/acme/shop/StatusGoesDownWithTheWorkersTest.java),
which occupies every worker and sees the status endpoint fail with the rest, then
[`StatusStaysUpTest`](src/test/java/org/acme/shop/StatusStaysUpTest.java), the same with the non-blocking
endpoint switched in by a `@TestProfile`. The two are
[`StatusResource`](src/main/java/org/acme/shop/StatusResource.java) and
[`NonBlockingStatusResource`](src/main/java/org/acme/shop/NonBlockingStatusResource.java).

```bash
./mvnw -pl samples/blocking-status-endpoint test
```
