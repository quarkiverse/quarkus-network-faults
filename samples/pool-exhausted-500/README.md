# Pool exhausted: 500

**The bug.** Every connection of the pool is in use. The next `getConnection()` waits the acquisition timeout and
throws, nothing maps the exception, and the client gets `500 Internal Server Error`. A 500 says the request is
broken, so load balancers and clients retry it at once, against a pool that is already full. In a `@QuarkusTest`
the pool is never full, so the 500 is first seen in production.

**The fix.** An exception mapper that answers `503 Service Unavailable` with `Retry-After`: the balancer tries
another instance, the client waits before trying again, and the pool gets a chance to drain.

Start with [`OrdersCrashWith500Test`](src/test/java/org/acme/orders/OrdersCrashWith500Test.java), which exhausts
the pool and gets the 500, then [`OrdersAnswer503Test`](src/test/java/org/acme/orders/OrdersAnswer503Test.java),
the same with the mapper switched on by a `@TestProfile`. The mapper is
[`PoolTimeoutMapper`](src/main/java/org/acme/orders/PoolTimeoutMapper.java).

```bash
./mvnw -pl samples/pool-exhausted-500 test
```
