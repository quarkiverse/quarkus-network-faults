package io.quarkiverse.resiliencytesting.lc4j;

import static io.quarkiverse.resiliencytesting.Moment.afterBytes;
import static io.quarkiverse.resiliencytesting.Moment.onNextWrite;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkiverse.resiliencytesting.runtime.internal.TargetSnapshot;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The same faults against three model providers behind Quarkus LangChain4j AI services, with SmallRye Fault
 * Tolerance around the call.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(LlmStubs.class)
public class LangChain4jScenariosTest {

    @Inject
    Faults faults;

    @Inject
    Instance<ChatModel> defaultModel;

    @Inject
    @ModelName("m-ollama")
    Instance<ChatModel> ollamaModel;

    private static String path(String provider) {
        return "/summarize/" + provider + "?text=long+text";
    }

    /**
     * The property the target's proxy was created from.
     */
    private String sourceProperty(Target target) {
        for (TargetSnapshot snapshot : ((FaultsImpl) faults).snapshot()) {
            if (snapshot.name().equals(target.name())) {
                return snapshot.sourceProperty();
            }
        }
        throw new AssertionError("no proxy behind " + target.name());
    }

    @Test
    public void theDefaultModelResolvesWithoutAQualifier() {
        assertEquals("langchain4j:openai:<default>", faults.target(ChatModel.class).name());
        assertEquals(faults.target(ChatModel.class).name(), faults.target(DefaultSummarizer.class).name());
    }

    @Test
    public void anInjectedInstanceResolvesLikeItsType() {
        assertEquals(faults.target(ChatModel.class).name(), faults.target(defaultModel).name());
        assertEquals(faults.target(ChatModel.class, ModelName.Literal.of("m-ollama")).name(),
                faults.target(ollamaModel).name());
    }

    @Test
    public void theDefaultModelUnavailable() {
        when()
                .get(path("default"))
                .then()
                .statusCode(200)
                .body(is("summary from openai"));

        try (ActiveFault f = faults.target(ChatModel.class).unavailable()) {
            when()
                    .get(path("default"))
                    .then()
                    .statusCode(503)
                    .body(is("model unavailable"));
        }
    }

    @Test
    public void aiServiceInterfaceResolvesToItsModel() {
        assertEquals("langchain4j:openai:m-openai", faults.target(OpenAiSummarizer.class).name());
        assertEquals("langchain4j:ollama:m-ollama", faults.target(OllamaSummarizer.class).name());
        assertEquals(faults.target(ChatModel.class, ModelName.Literal.of("m-anthropic")).name(),
                faults.target(AnthropicSummarizer.class).name());
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void modelCallsGoThroughTheProxy(String provider) {
        Target model = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider));
        long requestsBefore = model.traffic().httpRequestsSeen();
        when()
                .get(path(provider))
                .then()
                .statusCode(200)
                .body(is("summary from " + provider));
        assertEquals(1, model.traffic().httpRequestsSeen() - requestsBefore, model.traffic().httpRequests().toString());
        String sourceProperty = sourceProperty(model);
        assertTrue(sourceProperty.startsWith("quarkus.langchain4j." + provider + "."), sourceProperty);
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void modelHangsIsBoundedByTheClientTimeoutNotTheFtTimeout(String provider) {
        // Finding: @Timeout(1000) fires and interrupts the thread, but the blocking model client keeps waiting,
        // so the caller only gets the fallback once quarkus.langchain4j.timeout (2s) expires, once per attempt
        // (the client retries once). Without that client timeout the request would hang for as long as the model.
        try (ActiveFault f = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider)).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get(path(provider))
                    .then()
                    .statusCode(503)
                    .body(is("model unavailable")));
            assertTrue(ms >= 1_900, "@Timeout alone cannot cut a blocking model call, took only " + ms + "ms");
            assertTrue(ms < 9_000, "the client timeout must bound the hang, took " + ms + "ms");
        }
        when()
                .get(path(provider))
                .then()
                .statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void modelUnavailableTakesTheFallback(String provider) {
        when()
                .post("/summarize/last-failure/clear")
                .then()
                .statusCode(204);
        try (ActiveFault f = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider)).unavailable()) {
            long ms = Stopwatch.millis(() -> when()
                    .get(path(provider))
                    .then()
                    .statusCode(503)
                    .body(is("model unavailable")));
            // a refused connection fails fast; the client's single retry may add a short backoff
            assertTrue(ms < 2_500, "a refused connection should fail fast, took " + ms + "ms");
        }
        String failure = when()
                .get("/summarize/last-failure")
                .then()
                .extract()
                .asString()
                .toLowerCase();
        assertTrue(failure.contains("connect") || failure.contains("refused"), failure);
        when()
                .get(path(provider))
                .then()
                .statusCode(200); // breaker reset by the extension
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void responseCutMidWayTakesTheFallback(String provider) {
        Target model = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider));
        long requestsBefore = model.traffic().httpRequestsSeen();
        try (ActiveFault f = model.diesMidRequest(afterBytes(40))) {
            when()
                    .get(path(provider))
                    .then()
                    .statusCode(503);
        }
        // Finding: every provider's client retries once on an I/O error, so a cut response costs two prompts.
        // httpRequestsSeen() is what makes that visible; a paid model would bill both.
        assertEquals(2, model.traffic().httpRequestsSeen() - requestsBefore, "one attempt plus the client's retry: "
                + model.traffic().httpRequests());
        when()
                .get(path(provider))
                .then()
                .statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void rateLimitedModelTakesTheFallback(String provider) {
        when()
                .post("/summarize/last-failure/clear")
                .then()
                .statusCode(204);

        Target model = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider));
        long requestsBefore = model.traffic().httpRequestsSeen();
        try (ActiveFault f = model.http().respondsWith(429)
                .contentType("application/json")
                .body("{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}")) {
            when()
                    .get(path(provider))
                    .then()
                    .statusCode(503)
                    .body(is("model unavailable"));
        }
        // Finding: a 429 is retried like an I/O error, so a rate-limited prompt is sent twice.
        assertEquals(2, model.traffic().httpRequestsSeen() - requestsBefore, "one attempt plus the client's retry: "
                + model.traffic().httpRequests());
        String failure = when()
                .get("/summarize/last-failure")
                .then()
                .extract()
                .asString();
        // LangChain4j maps the status to its RateLimitException
        assertTrue(failure.contains("RateLimitException"), failure);

        when()
                .get(path(provider))
                .then()
                .statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void aSingleRateLimitIsAbsorbedByTheClientRetry(String provider) {
        Target model = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider));
        long requestsBefore = model.traffic().httpRequestsSeen();
        try (ActiveFault f = model.http().forFirst(1).http().respondsWith(429)) {
            when()
                    .get(path(provider))
                    .then()
                    .statusCode(200)
                    .body(is("summary from " + provider));
        }
        assertEquals(2, model.traffic().httpRequestsSeen() - requestsBefore, "the 429 and the retry that succeeded: "
                + model.traffic().httpRequests());
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "ollama", "anthropic" })
    public void openBreakerMakesNoModelCalls(String provider) {
        Target model = faults.target(ChatModel.class, ModelName.Literal.of("m-" + provider));
        try (ActiveFault f = model.diesMidRequest(onNextWrite())) {
            when()
                    .get(path(provider))
                    .then()
                    .statusCode(503);

            when()
                    .get(path(provider))
                    .then()
                    .statusCode(503);
            long before = model.traffic().httpRequestsSeen();
            when()
                    .get(path(provider))
                    .then()
                    .statusCode(503); // breaker open: no prompt sent
            assertEquals(before, model.traffic().httpRequestsSeen());
            assertTrue(when()
                    .get("/summarize/last-failure")
                    .then()
                    .extract()
                    .asString()
                    .contains("CircuitBreakerOpen"));
        }
        when()
                .get(path(provider))
                .then()
                .statusCode(200);
    }
}
