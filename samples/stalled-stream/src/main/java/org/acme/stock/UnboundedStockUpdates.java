package org.acme.stock;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.arc.properties.UnlessBuildProperty;
import io.smallrye.mutiny.Multi;

/**
 * The bug: the stream as the client delivers it, with nothing of its own to bound a silence. The client's read
 * timeout looks like that bound and is not: it is cancelled when the response headers arrive, and from then on a
 * stream that stops producing events is waited for as long as the connection stays open.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "stock.idle-timeout", stringValue = "true", enableIfMissing = true)
public class UnboundedStockUpdates implements StockUpdates {

    private final InventoryClient inventory;

    public UnboundedStockUpdates(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    @Override
    public Multi<String> updates() {
        return inventory.stockUpdates();
    }
}
