package io.quarkiverse.networkfaults.kafka;

import java.util.Map;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(UpstreamKafkaNativeFaultsTest.Profile.class)
public class UpstreamKafkaNativeFaultsTest extends AbstractKafkaFaultsTest {

    public static class Profile extends NetworkFaultsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.network-faults.enabled", "true",
                    "quarkus.kafka.devservices.provider", "upstream-kafka-native");
        }
    }
}
