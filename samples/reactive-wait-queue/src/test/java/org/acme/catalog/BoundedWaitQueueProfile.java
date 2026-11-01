package org.acme.catalog;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class BoundedWaitQueueProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // no wait queue: a query that finds no free connection fails at once instead of waiting for one
        return Map.of("quarkus.datasource.reactive.max-wait-queue-size", "0");
    }
}
