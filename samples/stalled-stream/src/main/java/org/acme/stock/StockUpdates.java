package org.acme.stock;

import io.smallrye.mutiny.Multi;

public interface StockUpdates {

    Multi<String> updates();
}
