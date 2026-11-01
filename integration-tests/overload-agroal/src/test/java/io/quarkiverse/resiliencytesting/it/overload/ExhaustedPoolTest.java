package io.quarkiverse.resiliencytesting.it.overload;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class ExhaustedPoolTest {

    @Inject
    Faults faults;

    @Inject
    DataSource dataSource;

    @Inject
    @io.quarkus.agroal.DataSource("audit")
    Instance<DataSource> auditDataSource;

    @Test
    void exhaustedPoolAnswersServiceUnavailableThenRecovers() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when()
                    .get("/orders")
                    .then()
                    .statusCode(503)
                    .header("Retry-After", "1");
        }

        when()
                .get("/orders")
                .then()
                .statusCode(200);
    }

    @Test
    void exhaustedPoolInsideATransaction() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders/transactional").then().statusCode(503);
        }

        when().get("/orders/transactional").then().statusCode(200);
    }

    @Test
    void readinessFailsWhileLivenessSurvivesWhenPoolIsExhausted() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            // readiness checks the datasource, so Kubernetes stops sending traffic to this pod
            when().get("/q/health/ready").then().statusCode(503);
            // liveness does not touch the database, so Kubernetes does not kill and restart the pod
            when().get("/q/health/live").then().statusCode(200);
        }

        when().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void namedDatasourceWithStrictAcquisitionIsExhaustedOnItsOwn() {
        var audit = new io.quarkus.agroal.DataSource.DataSourceLiteral("audit");
        try (var ignored = faults.pool(DataSource.class, audit).exhausted()) {
            when().get("/orders/audit").then().statusCode(503);
            // the default datasource is untouched
            when().get("/orders").then().statusCode(200);
        }

        when().get("/orders/audit").then().statusCode(200);
    }

    @Test
    void circuitBreakerOpenedByTheFaultIsResetWhenItEnds() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders/guarded").then().statusCode(503).header("X-Reason", "no-connection");
            when().get("/orders/guarded").then().statusCode(503).header("X-Reason", "no-connection");
            // two failures: the breaker is open now
            when().get("/orders/guarded").then().statusCode(503).header("X-Reason", "circuit-open");
        }

        // the connections are back and the breaker was reset, so the recovery shows in the same test
        when().get("/orders/guarded").then().statusCode(200);
    }

    @Test
    void poolIsNamedAfterTheDatasource() {
        assertEquals("datasource:<default>:jdbc", faults.pool(DataSource.class).name());
        assertEquals("datasource:audit:jdbc", faults.pool(auditDataSource).name());
    }

    @Test
    void secondFaultOnABusyTargetIsRefused() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            var e = assertThrows(IllegalStateException.class, () -> faults.pool(DataSource.class).exhausted());
            assertTrue(e.getMessage().contains("datasource:<default>"), e.getMessage());
            when().get("/orders").then().statusCode(503);
        }
    }

    @Test
    void faultNothingWaitedOnIsReportedByTheGuard() {
        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            // this endpoint never touches the database, so the fault cannot have been tested
            when().get("/orders/static").then().statusCode(200);
        }

        // read the guard directly; the after-each callback would otherwise fail this test
        List<String> violations = ((FaultsImpl) faults).takeGuardViolations();
        assertEquals(1, violations.size(), violations::toString);
        assertTrue(violations.get(0).contains("datasource:<default>:jdbc"), violations.get(0));
    }

    @Test
    void aWindowLeftOpenIsClosedForTheNextTest_first() {
        faults.pool(DataSource.class).exhausted(); // deliberately not closed
        when().get("/orders").then().statusCode(503);
    }

    @Test
    void aWindowLeftOpenIsClosedForTheNextTest_second() {
        when().get("/orders").then().statusCode(200);
    }

    @Test
    void aPoolHeldByTheApplicationIsRefusedThenAvailable() throws Exception {
        try (Connection held = dataSource.getConnection()) {
            var e = assertThrows(IllegalStateException.class, () -> faults.pool(DataSource.class).exhausted());
            assertTrue(e.getMessage().contains("Could not take all"), e.getMessage());
        }

        try (var ignored = faults.pool(DataSource.class).exhausted()) {
            when().get("/orders").then().statusCode(503);
        }
    }
}
