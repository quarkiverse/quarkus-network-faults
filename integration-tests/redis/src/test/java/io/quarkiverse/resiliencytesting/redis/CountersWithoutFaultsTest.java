package io.quarkiverse.resiliencytesting.redis;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CountersWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void countersCount() {
        when()
                .post("/counters/plain")
                .then()
                .statusCode(200)
                .body(is("1"));

        when()
                .get("/counters/plain")
                .then()
                .statusCode(200)
                .body(is("1"));
    }
}
