# Retry amplification

**The bug.** `@Retry` around the database call. It is there for the dropped connection, and it is harmless as long
as the pool has a free connection. When the pool is full, every attempt waits the whole acquisition timeout and
then asks the pool again: one client request is four callers queueing for a connection, and the client's own retry
makes it eight. The pool that was full is now besieged, and it cannot drain. The retries only ever run in
production, because a `@QuarkusTest` never has a full pool.

**The fix.** `@CircuitBreaker` instead. A failed request asks the pool once, and after two failures the breaker
opens and requests fail at once, without touching the pool. When connections are back, the breaker closes and the
application recovers.

Start with [`RetryMultipliesTheLoadTest`](src/test/java/org/acme/checkout/RetryMultipliesTheLoadTest.java),
which exhausts the pool and counts how often one request asks it for a connection, then
[`BreakerFailsFastTest`](src/test/java/org/acme/checkout/BreakerFailsFastTest.java), the same with the breaker
switched in by a `@TestProfile`. The two policies are
[`RetryingOrders`](src/main/java/org/acme/checkout/RetryingOrders.java) and
[`GuardedOrders`](src/main/java/org/acme/checkout/GuardedOrders.java).

```bash
./mvnw -pl samples/retry-amplification test
```
