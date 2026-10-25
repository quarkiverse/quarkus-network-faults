package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.Target;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@WithTestResource(value = KafkaCompanionResource.class, scope = TestResourceScope.GLOBAL)
class ShippingFailuresTest {

    @Inject
    NetworkFaults faults;

    @Inject
    Shipments shipments;

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @Test
    void poisonMessage() {
        long warm = placeOrder();
        await().until(() -> shipments.isScheduled(warm));

        long poison;
        Target shipping = faults.target(ShippingClient.class);
        try (var ignored = shipping.http().respondsWith(500)) {
            poison = placeOrder();
            var dead = companion.consume(Long.class)
                    .fromTopics("dead-letter-topic-orders-to-ship", 1)
                    .awaitCompletion();
            assertEquals(poison, dead.getFirstRecord().value());
        }

        long next = placeOrder();
        await().until(() -> shipments.isScheduled(next));
    }

    @Test
    void carrierSaysComeBackLater() {
        Target shipping = faults.target(ShippingClient.class);
        try (var ignored = shipping.http().forFirst(1)
                .http().respondsWith(429).header("Retry-After", "2")) {
            when()
                    .get("/orders/1/shipping-quote")
                    .then()
                    .statusCode(200);
        }

        var attempts = shipping.traffic().httpRequests();
        var limited = attempts.get(attempts.size() - 2);
        var retried = attempts.get(attempts.size() - 1);
        assertEquals(429, limited.responseStatus());
        Duration waited = Duration.between(limited.time(), retried.time());
        assertTrue(waited.compareTo(Duration.ofSeconds(2)) >= 0,
                "retried after " + waited);
    }

    private long placeOrder() {
        return when().post("/orders").then().statusCode(201).extract().jsonPath().getLong("id");
    }
}
