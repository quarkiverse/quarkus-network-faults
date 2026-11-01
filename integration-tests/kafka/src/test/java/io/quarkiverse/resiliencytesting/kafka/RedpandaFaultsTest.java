package io.quarkiverse.resiliencytesting.kafka;

import java.util.Map;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(RedpandaFaultsTest.Profile.class)
public class RedpandaFaultsTest extends AbstractKafkaFaultsTest {

    public static class Profile extends NetworkFaultsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.resiliency-testing.enabled", "true",
                    "quarkus.kafka.devservices.provider", "redpanda");
        }
    }
}
