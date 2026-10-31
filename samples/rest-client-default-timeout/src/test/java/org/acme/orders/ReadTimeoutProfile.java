package org.acme.orders;

import java.util.Map;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;

public class ReadTimeoutProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // one second: the longest an order should wait for the inventory before telling the caller to come back
        return Map.of(
                "quarkus.network-faults.enabled", "true",
                "quarkus.rest-client.inventory.read-timeout", "1000");
    }
}
