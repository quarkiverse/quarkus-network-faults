package io.quarkiverse.resiliencytesting.lc4jstores;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkiverse.langchain4j.EmbeddingStoreName;
import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The stores of Quarkus LangChain4j behind the proxy: the chat memory in Redis, the embeddings in pgvector and in
 * Chroma, all from Dev Services, each addressed by the store bean the application injects.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(OpenAiStub.class)
public class StoresTest {

    private static final String CHAT = "/chat/s1?text=hello";

    @Inject
    Faults faults;

    @Inject
    Instance<EmbeddingStore<TextSegment>> pgvector;

    @Inject
    @EmbeddingStoreName("c1")
    Instance<EmbeddingStore<TextSegment>> chroma;

    @Test
    public void theMemoryStoreIsItsRedisClient() {
        Target memory = faults.target(ChatMemoryStore.class);
        assertEquals("redis:<default>", memory.name());
        assertEquals(faults.target(RedisDataSource.class).name(), memory.name());
    }

    @Test
    public void thePgvectorStoreIsItsDatasource() {
        Target store = faults.target(EmbeddingStore.class);
        assertEquals("datasource:<default>:jdbc", store.name());
        assertEquals(faults.target(DataSource.class).name(), store.name());
        assertEquals(store.name(), faults.target(pgvector).name());
    }

    @Test
    public void theChromaStoreHasATargetOfItsOwn() {
        Target store = faults.target(EmbeddingStore.class, EmbeddingStoreName.Literal.of("c1"));
        assertEquals("langchain4j:chroma:c1", store.name());
        assertEquals(store.name(), faults.target(chroma).name());
    }

    @Test
    public void memoryStoreUnavailableFailsTheTurnBeforeAnyPromptIsSent() {
        when()
                .get(CHAT)
                .then()
                .statusCode(200)
                .body(is("reply from openai"));

        Target model = faults.target(ChatModel.class);
        long promptsBefore = model.traffic().httpRequestsSeen();
        try (ActiveFault f = faults.target(ChatMemoryStore.class).unavailable()) {
            when()
                    .get(CHAT)
                    .then()
                    .statusCode(503);
        }
        // the memory is read before the model is called: a failed read costs no prompt
        assertEquals(promptsBefore, model.traffic().httpRequestsSeen());

        when()
                .get(CHAT)
                .then()
                .statusCode(200);
    }

    @Test
    public void memoryStoreHangsIsBoundedByTheRedisTimeout() {
        try (ActiveFault f = faults.target(ChatMemoryStore.class).hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .get(CHAT)
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 1_900, "the turn must wait for quarkus.redis.timeout, took " + ms + "ms");
            assertTrue(ms < 9_000, "quarkus.redis.timeout must bound the hang, took " + ms + "ms");
        }
    }

    @Test
    public void pgvectorUnavailable() {
        when()
                .post("/embeddings/pgvector")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(EmbeddingStore.class).unavailable()) {
            when()
                    .post("/embeddings/pgvector")
                    .then()
                    .statusCode(503);
        }

        when()
                .post("/embeddings/pgvector")
                .then()
                .statusCode(200);
    }

    @Test
    public void chromaUnavailableAndFailing() {
        Target store = faults.target(EmbeddingStore.class, EmbeddingStoreName.Literal.of("c1"));
        when()
                .post("/embeddings/chroma")
                .then()
                .statusCode(200);

        try (ActiveFault f = store.unavailable()) {
            when()
                    .post("/embeddings/chroma")
                    .then()
                    .statusCode(503);
        }

        // Chroma is an HTTP API, so it takes the HTTP faults a model does
        long before = store.traffic().httpRequestsSeen();
        try (ActiveFault f = store.http().respondsWith(500)) {
            when()
                    .post("/embeddings/chroma")
                    .then()
                    .statusCode(503);
        }
        assertTrue(store.traffic().httpRequestsSeen() > before, store.traffic().httpRequests().toString());

        when()
                .post("/embeddings/chroma")
                .then()
                .statusCode(200);
    }
}
