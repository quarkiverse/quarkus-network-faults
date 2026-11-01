# Stalled stream

**The bug.** The application reads a stream of Server-Sent Events from the inventory service, and the client has a
read timeout of two seconds, so a stream that goes quiet looks bounded. It is not: the read timeout bounds the wait
for the response headers, and Vert.x cancels it once they arrive. A stream that delivers its first event and then
stops is waited for as long as the connection stays open, and nothing in the configuration says so.

**The fix.** An idle timeout on the stream itself, `ifNoItem().after(...)`: a silence longer than that between two
events fails the stream, and the consumer decides what to do with the events it has.

Start with [`StreamStallsForGoodTest`](src/test/java/org/acme/stock/StreamStallsForGoodTest.java), which stalls the
stream after its first event and shows the request still waiting after the read timeout has passed, then
[`StreamStallIsBoundedTest`](src/test/java/org/acme/stock/StreamStallIsBoundedTest.java), the same with the idle
timeout switched in by a `@TestProfile`. The two consumers are
[`UnboundedStockUpdates`](src/main/java/org/acme/stock/UnboundedStockUpdates.java) and
[`BoundedStockUpdates`](src/main/java/org/acme/stock/BoundedStockUpdates.java); the inventory service is a stub
the tests start, [`InventoryServer`](src/test/java/org/acme/stock/InventoryServer.java).

```bash
./mvnw -pl samples/stalled-stream test
```
