package io.quarkiverse.resiliencytesting.lc4jmcp;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A stand-in for an MCP server over the streamable HTTP transport, speaking the stateful protocol of 2025-11-25: every
 * message is a JSON-RPC request posted to one endpoint and answered in the response. It offers one tool,
 * {@code forecast}. It uses the Vert.x instance of the calling thread when there is one, and otherwise one of its own,
 * closed with it.
 */
public class McpServerStub implements QuarkusTestResourceLifecycleManager {

    private static final String PROTOCOL_VERSION = "2025-11-25";

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
                .requestHandler(McpServerStub::handle)
                .listen(0, "127.0.0.1").await();
        return Map.of("mcp.weather.url", "http://127.0.0.1:" + server.actualPort());
    }

    private static void handle(HttpServerRequest request) {
        if (!request.path().equals("/mcp") || request.method() != HttpMethod.POST) {
            request.response().setStatusCode(404).end();
            return;
        }
        request.body().onSuccess(body -> {
            JsonObject message = body.toJsonObject();
            String method = message.getString("method");
            // a notification, or the client's answer to a server request: nothing to say back
            if (method == null || method.startsWith("notifications/")) {
                request.response().setStatusCode(202).end();
                return;
            }
            JsonObject reply = new JsonObject().put("jsonrpc", "2.0").put("id", message.getValue("id"));
            JsonObject result = result(method, message.getJsonObject("params", new JsonObject()));
            if (result == null) {
                reply.put("error", new JsonObject().put("code", -32601).put("message", "unknown method " + method));
            } else {
                reply.put("result", result);
            }
            request.response()
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Mcp-Session-Id", "stub-session")
                    .end(reply.encode());
        });
    }

    private static JsonObject result(String method, JsonObject params) {
        return switch (method) {
            case "initialize" -> new JsonObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", new JsonObject().put("tools", new JsonObject()))
                    .put("serverInfo", new JsonObject().put("name", "weather-stub").put("version", "1"));
            case "ping" -> new JsonObject();
            case "tools/list" -> new JsonObject().put("tools", new JsonArray().add(new JsonObject()
                    .put("name", "forecast")
                    .put("description", "Tomorrow's weather in a city")
                    .put("inputSchema", new JsonObject()
                            .put("type", "object")
                            .put("properties", new JsonObject()
                                    .put("city", new JsonObject().put("type", "string"))))));
            case "tools/call" -> new JsonObject()
                    .put("content", new JsonArray().add(new JsonObject()
                            .put("type", "text")
                            .put("text", "sunny in " + params.getJsonObject("arguments").getString("city"))))
                    .put("isError", false);
            default -> null;
        };
    }

    @Override
    public void stop() {
        server.close().await();
        if (ownsVertx) {
            vertx.close().await();
        }
    }
}
