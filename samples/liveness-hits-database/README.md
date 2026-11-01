# Liveness hits the database

**The bug.** A liveness check that queries the database. Liveness answers "should this process be restarted?",
and a process whose connection pool is full is busy, not broken. With the pool full, the check waits the
acquisition timeout, reports down, and Kubernetes restarts the pod: every request in flight is dropped, and the
new process meets the same full database and fails the same check. One slow query becomes a restart loop across
the deployment. A `@QuarkusTest` never has a full pool, so both probes are always green.

**The fix.** The database is a readiness concern. Quarkus already reports every datasource in `/q/health/ready`,
so a pod that cannot get a connection leaves the rotation until it can, and the liveness probe does not touch the
database at all.

Start with [`BusyPodIsRestartedTest`](src/test/java/org/acme/inventory/BusyPodIsRestartedTest.java), which
exhausts the pool and sees both probes fail, then
[`BusyPodStaysUpTest`](src/test/java/org/acme/inventory/BusyPodStaysUpTest.java), the same with the database
liveness check switched off by a `@TestProfile`. The check is
[`DatabaseLivenessCheck`](src/main/java/org/acme/inventory/DatabaseLivenessCheck.java).

```bash
./mvnw -pl samples/liveness-hits-database test
```
