# Readiness calls a dependency

**The bug.** A readiness check that asks the inventory service whether it is up. Readiness answers "should traffic be
routed to this pod?", and when the inventory is down the honest answer is still yes: the pod can list orders, and it
can tell a caller that placing one has to wait. Instead it reports unready and leaves the rotation, and since every
pod asks the same inventory, so does every other. An outage of one dependency becomes an outage of the whole
service, and the dashboards show a service with no ready pods and nothing wrong with any of them.

**The fix.** Readiness reports the pod's own state, and the dependency's readiness is the dependency's to report.
The pod stays in the rotation, serves what it can, and answers 503 with `Retry-After` for what needs the inventory.

Start with [`InventoryOutageTakesThePodOutTest`](src/test/java/org/acme/orders/InventoryOutageTakesThePodOutTest.java),
which takes the inventory down and sees the readiness probe fail while the pod still serves requests, then
[`InventoryOutageDegradesThePodTest`](src/test/java/org/acme/orders/InventoryOutageDegradesThePodTest.java), the
same with the check switched off by a `@TestProfile`. The check is
[`InventoryReadinessCheck`](src/main/java/org/acme/orders/InventoryReadinessCheck.java); the inventory service is a
stub the tests start, [`InventoryServer`](src/test/java/org/acme/orders/InventoryServer.java).

```bash
./mvnw -pl samples/readiness-calls-dependency test
```
