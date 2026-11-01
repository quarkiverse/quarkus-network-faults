package org.acme.orders;

import java.util.Map;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;

public class ReadTimeoutProfile extends NetworkFaultsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // one second: the longest an order should wait for the inventory before telling the caller to come back
        return Map.of(
                "quarkus.resiliency-testing.enabled", "true",
                "quarkus.rest-client.inventory.read-timeout", "1000");
    }
}
