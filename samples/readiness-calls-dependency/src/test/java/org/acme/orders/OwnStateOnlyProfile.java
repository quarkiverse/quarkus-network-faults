package org.acme.orders;

import java.util.Map;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;

public class OwnStateOnlyProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.network-faults.enabled", "true",
                "orders.inventory-readiness", "false");
    }
}
