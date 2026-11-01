package io.quarkiverse.resiliencytesting.data;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class PeopleWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void bothRepositoriesAnswer() {
        when()
                .post("/people?name=plain")
                .then()
                .statusCode(200);

        when()
                .get("/people/count")
                .then()
                .statusCode(200);

        when()
                .get("/people/count/jakarta-data")
                .then()
                .statusCode(200);
    }
}
