package org.acme.reports;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.startsWith;
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

/**
 * The bug: with every worker busy, a blocking request is queued and waits for as long as it takes. The server
 * never says no, so the client learns nothing until its own deadline passes, and a client that retries puts a
 * second request behind the first.
 */
@QuarkusTest
class RequestsWaitForeverTest {

    @Inject
    Faults faults;

    @TestHTTPResource("/reports")
    URI reports;

    @Test
    void theClientGivesUpBeforeTheServerAnswers() {
        HttpClient client = HttpClient.newHttpClient();
        // a client with a deadline, as every client has
        HttpRequest request = HttpRequest.newBuilder(reports).timeout(Duration.ofSeconds(1)).build();
        try (var ignored = faults.workerPool().exhausted()) {
            // no worker is free, there is no limit to how long the request waits for one, and the deadline passes
            assertThrows(HttpTimeoutException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
            // the event loop is not involved: liveness still answers, so nothing restarts this pod either
            when().get("/q/health/live").then().statusCode(200);
        }

        when().get("/reports").then().statusCode(200).body(startsWith("report rendered on executor-thread-"));
    }
}
