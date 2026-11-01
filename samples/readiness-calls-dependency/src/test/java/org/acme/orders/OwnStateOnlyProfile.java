package org.acme.orders;

import java.util.Map;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;

public class OwnStateOnlyProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.resiliency-testing.enabled", "true",
                "orders.inventory-readiness", "false");
    }
}
