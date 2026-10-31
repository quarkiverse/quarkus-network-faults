# REST Client default timeout

**The bug.** The REST Client is configured with a URL and nothing else, so its timeouts are the defaults: fifteen
seconds to connect, thirty to read. An inventory service that accepts the connection and never answers holds every
order for thirty seconds, and with it the worker thread, the load balancer's slot and the customer. Nothing in the
application knows about that number, and a `@QuarkusTest` never meets it, because the inventory it talks to always
answers.

**The fix.** `quarkus.rest-client.inventory.read-timeout`: a bound the application chose, one second here, after
which the caller is told to come back later. The same effect comes from a `@Timeout` on the method that makes the
call; either way the number is the application's decision instead of a library default.

Start with [`OrdersHangWithTheInventoryTest`](src/test/java/org/acme/orders/OrdersHangWithTheInventoryTest.java),
which hangs the inventory and shows an order still waiting three seconds later, then
[`OrdersFailFastTest`](src/test/java/org/acme/orders/OrdersFailFastTest.java), the same with the read timeout set
by a `@TestProfile`. The inventory service is a stub the tests start,
[`InventoryServer`](src/test/java/org/acme/orders/InventoryServer.java); the hang is in the proxy between the
application and it.

```bash
./mvnw -pl samples/rest-client-default-timeout test
```
