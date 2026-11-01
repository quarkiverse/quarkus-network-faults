# Shared audit pool

**The bug.** The audit log writes through the same datasource as the orders. The audit table is the one that
grows without bound, gets reindexed, and is locked by the nightly export, so its writes are the ones that hold
connections for longer. Once they hold all of them, no order can be placed, although an order needs the audit
log for nothing. In a `@QuarkusTest` the pool is never full, so the two never compete.

**The fix.** A bulkhead: a named `audit` datasource with a pool of its own. A slow audit table uses up its own
pool and no other, and the audit log is best-effort anyway, so an order is placed even when its audit entry is
lost.

Start with [`AuditTakesOrdersDownTest`](src/test/java/org/acme/orders/AuditTakesOrdersDownTest.java), which
exhausts the pool the audit log writes to and sees the orders fail, then
[`OrdersSurviveAuditOutageTest`](src/test/java/org/acme/orders/OrdersSurviveAuditOutageTest.java), where a
`@TestProfile` activates the `audit` datasource and the test exhausts that one instead. The two audit logs are
[`SharedPoolAuditLog`](src/main/java/org/acme/orders/SharedPoolAuditLog.java) and
[`OwnPoolAuditLog`](src/main/java/org/acme/orders/OwnPoolAuditLog.java).

```bash
./mvnw -pl samples/shared-audit-pool test
```
