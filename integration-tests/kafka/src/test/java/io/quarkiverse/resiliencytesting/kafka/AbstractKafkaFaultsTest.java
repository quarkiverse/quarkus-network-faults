package io.quarkiverse.resiliencytesting.kafka;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.smallrye.reactive.messaging.MutinyEmitter;

abstract class AbstractKafkaFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void bootstrapConnectionGoesThroughTheProxy() {
        when()
                .post("/events?event=one")
                .then()
                .statusCode(202);
        Target kafka = faults.target(MutinyEmitter.class, channel("orders-out"));
        assertTrue(kafka.traffic().connectionsSeen() >= 1, "no connection went through the Kafka proxy " + kafka.name());
    }

    @Test
    public void faultsReachTheProducerAfterMetadataRefresh() {
        when()
                .post("/events?event=warm")
                .then()
                .statusCode(202);
        Target kafka = faults.target(MutinyEmitter.class, channel("orders-out"));
        kafka.dropConnections();
        try (ActiveFault f = kafka.unavailable()) {
            when()
                    .post("/events?event=during-fault")
                    .then()
                    .statusCode(503);
        }

        when()
                .post("/events?event=after-fault")
                .then()
                .statusCode(202);
    }

    static Channel channel(String name) {
        final class Literal extends AnnotationLiteral<Channel> implements Channel {
            @Override
            public String value() {
                return name;
            }
        }
        return new Literal();
    }
}
