package org.acme.orders;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

/**
 * A stand-in for the inventory service: a healthy upstream that reserves whatever it is asked for, on a port of its
 * own. The faults are not here, they are in the proxy the application reaches it through.
 */
public class InventoryServer implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(this::reserve).listen(0, "127.0.0.1").await();
        return Map.of("inventory.url", "http://127.0.0.1:" + server.actualPort());
    }

    private void reserve(HttpServerRequest request) {
        if (request.path().equals("/inventory/reserve")) {
            request.response().end("reserved");
        } else {
            request.response().setStatusCode(404).end();
        }
    }

    @Override
    public void stop() {
        server.close().await();
        vertx.close().await();
    }
}
