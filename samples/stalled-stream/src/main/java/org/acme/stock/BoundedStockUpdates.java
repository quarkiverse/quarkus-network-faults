package org.acme.stock;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;

/**
 * The fix: an idle timeout on the stream itself. A silence longer than this between two events fails the stream,
 * and the consumer gets to decide what to do with the events it has, instead of waiting for one that never comes.
 */
@ApplicationScoped
@IfBuildProperty(name = "stock.idle-timeout", stringValue = "true")
public class BoundedStockUpdates implements StockUpdates {

    // the longest the inventory goes quiet between two updates while it is working
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(1);

    private final InventoryClient inventory;

    public BoundedStockUpdates(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    @Override
    public Multi<String> updates() {
        return inventory.stockUpdates().ifNoItem().after(IDLE_TIMEOUT).fail();
    }
}
