package org.acme.catalog;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;

/**
 * The bug: with every connection in use, a query joins the pool's wait queue and stays there for up to the
 * connection timeout, thirty seconds by default. Nothing says no, so the client learns nothing until its own
 * deadline passes, and a client that retries puts a second query behind the first.
 */
@QuarkusTest
class QueriesPileUpTest {

    @Inject
    Faults faults;

    @TestHTTPResource("/catalog")
    URI catalog;

    @Test
    void theClientGivesUpBeforeTheServerAnswers() {
        HttpClient client = HttpClient.newHttpClient();
        // a client with a deadline, as every client has
        HttpRequest request = HttpRequest.newBuilder(catalog).timeout(Duration.ofSeconds(1)).build();
        try (var ignored = faults.pool(Pool.class).exhausted()) {
            // the query is in the wait queue with thirty seconds to go, and the deadline passes
            assertThrows(HttpTimeoutException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
        }

        when().get("/catalog").then().statusCode(200);
    }
}
