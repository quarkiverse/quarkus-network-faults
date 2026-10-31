package org.acme.reports;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class BoundedQueueProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // no queue in front of the workers: a request that finds none free is rejected, and Quarkus answers 503
        return Map.of("quarkus.thread-pool.queue-size", "0");
    }
}
