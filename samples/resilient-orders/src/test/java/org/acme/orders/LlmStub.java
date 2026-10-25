package org.acme.orders;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

/**
 * A stand-in for the OpenAI chat completions API, a Vert.x HTTP server answering every request with the same canned
 * completion, so that the tests need no API key and no network.
 */
public class LlmStub implements QuarkusTestResourceLifecycleManager {

    private static final String COMPLETION = """
            {"id":"cmpl-1","object":"chat.completion","created":1,"model":"gpt-test",
             "choices":[{"index":0,"message":{"role":"assistant","content":"A short summary."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private Vertx vertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer()
                .requestHandler(request -> request.response()
                        .putHeader("Content-Type", "application/json")
                        .end(COMPLETION))
                .listen(0, "127.0.0.1").await();
        return Map.of("llm.url", "http://127.0.0.1:" + server.actualPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close().await();
        }
        if (vertx != null) {
            vertx.close().await();
        }
    }
}
