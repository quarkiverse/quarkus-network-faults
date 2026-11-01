package org.acme.orders;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

/**
 * A stand-in for the inventory service: a healthy upstream on a port of its own. The outage is not here, it is in
 * the proxy the application reaches it through.
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
        switch (request.path()) {
            case "/inventory/reserve" -> request.response().end("reserved");
            case "/inventory/status" -> request.response().end("up");
            default -> request.response().setStatusCode(404).end();
        }
    }

    @Override
    public void stop() {
        server.close().await();
        vertx.close().await();
    }
}
