package io.quarkiverse.resiliencytesting.lc4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Minimal stand-ins for three model APIs, each a Vert.x HTTP server on its own port, answering a canned completion.
 * The OpenAI one also streams: a request with {@code "stream":true} gets the completion as three Server-Sent
 * Events of one token each. They use the Vert.x instance of the calling thread when there is one, and otherwise one
 * of their own, closed with them.
 */
public class LlmStubs implements QuarkusTestResourceLifecycleManager {

    private static final List<String> PROVIDERS = List.of("openai", "ollama", "anthropic");

    private Vertx vertx;
    private boolean ownsVertx;
    private final List<HttpServer> servers = new ArrayList<>();

    private static String body(String provider) {
        return switch (provider) {
            case "openai" ->
                """
                        {"id":"cmpl-1","object":"chat.completion","created":1,"model":"test",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"summary from openai"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                        """;
            case "ollama" ->
                """
                        {"model":"llama-test","created_at":"2024-05-03T10:27:56Z",
                         "message":{"role":"assistant","content":"summary from ollama"},"done":true,
                         "total_duration":1,"load_duration":1,"prompt_eval_count":5,"prompt_eval_duration":1,"eval_count":3,"eval_duration":1}
                        """;
            case "anthropic" -> """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                     "content":[{"type":"text","text":"summary from anthropic"}],
                     "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":3}}
                    """;
            default -> throw new IllegalArgumentException(provider);
        };
    }

    private static String path(String provider) {
        return switch (provider) {
            case "openai" -> "/v1/chat/completions";
            case "ollama" -> "/api/chat";
            case "anthropic" -> "/v1/messages";
            default -> throw new IllegalArgumentException(provider);
        };
    }

    @Override
    public Map<String, String> start() {
        io.vertx.core.Context current = Vertx.currentContext();
        if (current != null) {
            vertx = current.owner();
        } else {
            vertx = Vertx.vertx();
            ownsVertx = true;
        }
        Map<String, String> config = new HashMap<>();
        for (String provider : PROVIDERS) {
            HttpServer server = vertx.createHttpServer()
                    .requestHandler(request -> complete(provider, request))
                    .listen(0, "127.0.0.1").await();
            servers.add(server);
            config.put("llm." + provider + ".url", "http://127.0.0.1:" + server.actualPort());
        }
        return config;
    }

    /**
     * Reads the whole prompt before answering, as a real API would, then sends the canned completion.
     */
    private static void complete(String provider, HttpServerRequest request) {
        if (!request.path().equals(path(provider))) {
            request.response().setStatusCode(404).end();
            return;
        }
        request.body().onSuccess(prompt -> {
            if (provider.equals("openai") && prompt.toJsonObject().getBoolean("stream", false)) {
                streamOpenAi(request.response());
            } else {
                request.response()
                        .putHeader("Content-Type", "application/json")
                        .end(body(provider));
            }
        });
    }

    private static String openAiChunk(String role, String content, String finishReason) {
        return "data: {\"id\":\"cmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\","
                + "\"choices\":[{\"index\":0,\"delta\":{" + (role == null ? "" : "\"role\":\"" + role + "\",")
                + "\"content\":\"" + content + "\"},\"finish_reason\":" + finishReason + "}]}\n\n";
    }

    /**
     * The tokens of "summary from openai", one event per chunk, then the {@code [DONE]} marker.
     */
    private static void streamOpenAi(HttpServerResponse response) {
        response.setChunked(true).putHeader("Content-Type", "text/event-stream");
        response.write(openAiChunk("assistant", "summary", "null"));
        response.write(openAiChunk(null, " from", "null"));
        response.write(openAiChunk(null, " openai", "\"stop\""));
        response.end("data: [DONE]\n\n");
    }

    @Override
    public void stop() {
        for (HttpServer server : servers) {
            server.close().await();
        }
        if (ownsVertx) {
            vertx.close().await();
        }
    }
}
