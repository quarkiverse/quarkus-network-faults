package io.quarkiverse.resiliencytesting.hr;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class FruitsWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void fruitsAreStored() {
        when()
                .post("/fruits?name=plain")
                .then()
                .statusCode(200)
                .body(is("created"));

        when()
                .get("/fruits/count")
                .then()
                .statusCode(200);
    }
}
