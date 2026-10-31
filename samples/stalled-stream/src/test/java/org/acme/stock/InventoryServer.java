package org.acme.stock;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * A stand-in for the inventory service, on a port of its own: three stock updates as Server-Sent Events, each in a
 * chunk of its own, and the stream ends. The stall is not here, it is in the proxy the application reaches it
 * through.
 */
public class InventoryServer implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(this::handle).listen(0, "127.0.0.1").await();
        return Map.of("inventory.url", "http://127.0.0.1:" + server.actualPort());
    }

    private void handle(HttpServerRequest request) {
        if (!request.path().equals("/inventory/stock-updates")) {
            request.response().setStatusCode(404).end();
            return;
        }
        HttpServerResponse response = request.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
        response.write("data: 1\n\n");
        response.write("data: 2\n\n");
        response.end("data: 3\n\n");
    }

    @Override
    public void stop() {
        server.close().await();
        vertx.close().await();
    }
}
