package io.quarkiverse.networkfaults.kafka;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Verifies that network faults reach the Kafka producer. The Kafka DevServices customizer makes the broker
 * advertise the proxy port, so the client reconnects through the proxy after metadata refresh.
 * Uses the default DevServices provider (upstream-kafka-native).
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class KafkaBootstrapOnlyTest extends AbstractKafkaFaultsTest {
}
