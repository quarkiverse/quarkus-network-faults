package org.acme.stock;

import java.util.Map;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;

public class IdleTimeoutProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.resiliency-testing.enabled", "true",
                "stock.idle-timeout", "true");
    }
}
