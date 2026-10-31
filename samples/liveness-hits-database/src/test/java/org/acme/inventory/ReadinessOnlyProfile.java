package org.acme.inventory;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ReadinessOnlyProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("inventory.database-liveness", "false");
    }
}
