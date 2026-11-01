package org.acme.shop;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class NonBlockingStatusProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("shop.status-non-blocking", "true");
    }
}
