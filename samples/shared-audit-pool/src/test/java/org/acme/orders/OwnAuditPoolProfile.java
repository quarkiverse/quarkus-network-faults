package org.acme.orders;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class OwnAuditPoolProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "orders.audit-pool", "own",
                "quarkus.datasource.\"audit\".active", "true");
    }
}
