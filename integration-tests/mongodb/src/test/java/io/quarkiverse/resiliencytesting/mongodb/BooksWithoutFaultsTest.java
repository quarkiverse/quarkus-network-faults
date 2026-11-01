package io.quarkiverse.resiliencytesting.mongodb;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The application as an ordinary test sees it: no profile, so MongoDB is reached directly.
 */
@QuarkusTest
public class BooksWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void booksAreStored() {
        when()
                .post("/books?title=plain")
                .then()
                .statusCode(200)
                .body(is("added"));

        when()
                .get("/books/count")
                .then()
                .statusCode(200);
    }
}
