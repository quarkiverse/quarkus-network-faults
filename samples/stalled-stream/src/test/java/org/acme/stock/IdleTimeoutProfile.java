package org.acme.stock;

import java.util.Map;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;

public class IdleTimeoutProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.network-faults.enabled", "true",
                "stock.idle-timeout", "true");
    }
}
