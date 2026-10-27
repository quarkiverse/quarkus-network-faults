package io.quarkiverse.networkfaults.it;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

public class InventoryServer implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private boolean ownsVertx;
    private HttpServer inventory;
    private HttpServer pricing;

    @Override
    public Map<String, String> start() {
        io.vertx.core.Context current = Vertx.currentContext();
        if (current != null) {
            vertx = current.owner();
        } else {
            vertx = Vertx.vertx();
            ownsVertx = true;
        }
        inventory = vertx.createHttpServer().requestHandler(this::inventory).listen(0, "127.0.0.1").await();
        try {
            pricing = vertx.createHttpServer().requestHandler(this::price).listen(PricingClient.PORT, "127.0.0.1").await();
        } catch (RuntimeException e) {
            throw new IllegalStateException("port " + PricingClient.PORT + " must be free for the pricing stub", e);
        }
        return Map.of("inventory.url", "http://127.0.0.1:" + inventory.actualPort());
    }

    private void inventory(HttpServerRequest request) {
        switch (request.path()) {
            case "/inventory/reserve" -> request.response().end("reserved");
            case "/inventory/stock-updates" -> stockUpdates(request.response());
            case "/inventory/export" -> export(request);
            default -> request.response().setStatusCode(404).end();
        }
    }

    /**
     * Three Server-Sent Events, each its own chunk.
     */
    private static void stockUpdates(HttpServerResponse response) {
        response.setChunked(true).putHeader("Content-Type", "text/event-stream");
        response.write("data: 1\n\n");
        response.write("data: 2\n\n");
        response.end("data: 3\n\n");
    }

    /**
     * Counts the bytes of an upload and answers with the count once it ends.
     */
    private static void export(HttpServerRequest request) {
        AtomicLong received = new AtomicLong();
        request.handler(buffer -> received.addAndGet(buffer.length()));
        request.endHandler(v -> request.response().end(String.valueOf(received.get())));
    }

    private void price(HttpServerRequest request) {
        if (!request.path().equals("/pricing")) {
            request.response().setStatusCode(404).end();
            return;
        }
        request.response().end("9.99");
    }

    @Override
    public void stop() {
        if (inventory != null) {
            inventory.close().await();
        }
        if (pricing != null) {
            pricing.close().await();
        }
        if (ownsVertx) {
            vertx.close().await();
        }
    }
}
