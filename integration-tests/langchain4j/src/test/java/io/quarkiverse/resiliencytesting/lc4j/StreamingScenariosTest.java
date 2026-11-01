package io.quarkiverse.resiliencytesting.lc4j;

import static io.quarkiverse.resiliencytesting.Moment.Http.afterSseEvents;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * A streamed model response that is cut or stalls after the first token.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(LlmStubs.class)
public class StreamingScenariosTest {

    private static final String PATH = "/summarize/stream/openai?text=long+text";

    @Inject
    Faults faults;

    private static void streamCompletes() {
        when()
                .get(PATH)
                .then()
                .statusCode(200)
                .body(is("summary from openai"));
    }

    @Test
    public void theStreamingModelResolvesToTheSameTargetAsTheChatModel() {
        assertEquals("langchain4j:openai:m-openai", faults.target(OpenAiStreamingSummarizer.class).name());
        assertEquals("langchain4j:openai:m-openai",
                faults.target(StreamingChatModel.class, ModelName.Literal.of("m-openai")).name());
    }

    @Test
    public void streamWithoutFaults() {
        streamCompletes();
    }

    @Test
    public void streamCutAfterTheFirstToken() {
        Target model = faults.target(OpenAiStreamingSummarizer.class);
        long requestsBefore = model.traffic().httpRequestsSeen();
        try (ActiveFault f = model.diesMidRequest(afterSseEvents(1))) {
            when()
                    .get(PATH)
                    .then()
                    .statusCode(502)
                    .body(containsString(" after [summary]"));
        }
        assertEquals(1, model.traffic().httpRequestsSeen() - requestsBefore,
                "a stream cut after tokens were emitted is not retried: " + model.traffic().httpRequests());
        streamCompletes();
    }

    @Test
    public void streamStallsAfterTheFirstToken() {
        Target model = faults.target(OpenAiStreamingSummarizer.class);
        try (ActiveFault f = model.hangs(afterSseEvents(1))) {
            long start = System.nanoTime();
            when()
                    .get(PATH)
                    .then()
                    .statusCode(502)
                    .body(containsString(" after [summary]"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            // quarkus.langchain4j.timeout (2s) is a read-timeout, cancelled once the response headers arrive; the
            // resource's own 3s idle timeout on the token stream is what bounds the stall
            assertTrue(ms >= 2_900 && ms < 6_000, "expected the resource's 3s idle timeout to bound the stall, took "
                    + ms + "ms");
        }
        streamCompletes();
    }
}
