package io.quarkiverse.resiliencytesting.lc4jstores;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

/**
 * A stand-in for the OpenAI chat API, a Vert.x HTTP server answering a canned completion once it has read the whole
 * prompt. It uses the Vert.x instance of the calling thread when there is one, and otherwise one of its own, closed
 * with it.
 */
public class OpenAiStub implements QuarkusTestResourceLifecycleManager {

    private static final String COMPLETION = """
            {"id":"cmpl-1","object":"chat.completion","created":1,"model":"test",
             "choices":[{"index":0,"message":{"role":"assistant","content":"reply from openai"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private Vertx vertx;
    private boolean ownsVertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        io.vertx.core.Context current = Vertx.currentContext();
        if (current != null) {
            vertx = current.owner();
        } else {
            vertx = Vertx.vertx();
            ownsVertx = true;
        }
        server = vertx.createHttpServer()
                .requestHandler(OpenAiStub::complete)
                .listen(0, "127.0.0.1").await();
        return Map.of("llm.openai.url", "http://127.0.0.1:" + server.actualPort());
    }

    private static void complete(HttpServerRequest request) {
        if (!request.path().equals("/v1/chat/completions")) {
            request.response().setStatusCode(404).end();
            return;
        }
        request.body().onSuccess(prompt -> request.response()
                .putHeader("Content-Type", "application/json")
                .end(COMPLETION));
    }

    @Override
    public void stop() {
        server.close().await();
        if (ownsVertx) {
            vertx.close().await();
        }
    }
}
