package org.acme.stock;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class BlockingProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("stock.blocking", "true");
    }
}
