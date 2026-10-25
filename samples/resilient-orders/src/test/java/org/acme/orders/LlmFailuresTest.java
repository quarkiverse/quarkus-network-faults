package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.Target;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@WithTestResource(value = LlmStub.class, scope = TestResourceScope.GLOBAL)
class LlmFailuresTest {

    @Inject
    NetworkFaults faults;

    @Test
    void providerRateLimitsOnce() {
        Target model = faults.target(ChatModel.class,
                ModelName.Literal.of("assistant"));
        long before = model.traffic().httpRequestsSeen();
        try (var ignored = model.http().forFirst(1).http().respondsWith(429)) {
            when()
                    .get("/summary")
                    .then()
                    .statusCode(200);
        }
        // the 429 and the retry
        assertEquals(2, model.traffic().httpRequestsSeen() - before);
    }

    @Test
    void providerKeepsRateLimiting() {
        Target model = faults.target(ChatModel.class,
                ModelName.Literal.of("assistant"));
        long before = model.traffic().httpRequestsSeen();
        try (var ignored = model.http().respondsWith(429)) {
            when()
                    .get("/summary")
                    .then()
                    .statusCode(503);
        }
        // no more than the client's own retry
        assertEquals(2, model.traffic().httpRequestsSeen() - before);
    }
}
