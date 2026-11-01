package io.quarkiverse.resiliencytesting.lc4j;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The sample application as an ordinary test sees it: no profile, so every model is reached directly.
 */
@QuarkusTest
@QuarkusTestResource(LlmStubs.class)
public class SummariesWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void everyProviderAnswers(String provider) {
        when()
                .get("/summarize/" + provider + "?text=long+text")
                .then()
                .statusCode(200)
                .body(is("summary from " + provider));

        when()
                .get("/summarize/last-failure")
                .then()
                .statusCode(200)
                .body(is(""));
    }
}
