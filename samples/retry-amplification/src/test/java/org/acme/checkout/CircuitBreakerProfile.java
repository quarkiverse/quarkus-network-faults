package org.acme.checkout;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class CircuitBreakerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("checkout.circuit-breaker", "true");
    }
}
