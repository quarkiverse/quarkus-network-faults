package io.quarkiverse.resiliencytesting.it;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The application as an ordinary test sees it: no profile, so nothing is proxied, every dependency is reached
 * directly, and asking for a target explains how to enable faults.
 */
@QuarkusTest
@QuarkusTestResource(InventoryServer.class)
public class OrdersWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void usingTheBeanExplainsHowToEnableFaults() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> faults.target(DataSource.class).unavailable());
        assertTrue(e.getMessage().contains("NetworkFaultsProfile"), e.getMessage());
    }

    @Test
    public void ordersAreCreated() {
        long before = count();

        when()
                .post("/orders?item=plain")
                .then()
                .statusCode(201)
                .body(is("created"));

        when()
                .post("/orders/retrying?item=plain-retry")
                .then()
                .statusCode(201)
                .body(is("created"));

        assertEquals(before + 2, count());
    }

    @Test
    public void everyDependencyAnswers() {
        when()
                .get("/orders/price")
                .then()
                .statusCode(200)
                .body(is("9.99"));

        when()
                .get("/reactive/ping")
                .then()
                .statusCode(200)
                .body(is("pong"));

        when()
                .get("/orders/last-failure")
                .then()
                .statusCode(200)
                .body(is(""));
    }

    private long count() {
        return Long.parseLong(when()
                .get("/orders/count")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }
}
