# Blocking on the event loop

**The bug.** An endpoint that returns a `Uni`, because the rest of the API is reactive, and makes a JDBC call
inside. A method that returns a `Uni` is non-blocking by convention, so Quarkus runs it on an event loop. While the
pool has a free connection the call is over in a millisecond and nothing shows; Vert.x's blocked-thread warning
needs two seconds. When the pool is full, the call waits the acquisition timeout on the event loop, and every
connection that loop serves waits with it: a full pool takes down endpoints that never touch the database.

**The fix.** `@Blocking`, so the method runs on a worker thread, where a JDBC call may wait.

Start with [`JdbcBlocksTheEventLoopTest`](src/test/java/org/acme/stock/JdbcBlocksTheEventLoopTest.java), which
watches the event loops while the pool is quiet and sees nothing, then while the pool is full and sees the block,
then [`JdbcRunsOnAWorkerTest`](src/test/java/org/acme/stock/JdbcRunsOnAWorkerTest.java), the same with the
blocking endpoint switched in by a `@TestProfile`. The two are
[`StockResource`](src/main/java/org/acme/stock/StockResource.java) and
[`BlockingStockResource`](src/main/java/org/acme/stock/BlockingStockResource.java).

```bash
./mvnw -pl samples/blocking-on-event-loop test
```
