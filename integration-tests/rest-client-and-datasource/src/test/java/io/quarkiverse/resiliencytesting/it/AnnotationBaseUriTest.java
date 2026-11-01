package io.quarkiverse.resiliencytesting.it;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class AnnotationBaseUriTest {

    @Inject
    Faults faults;

    @Test
    public void annotationBaseUriIsProxied() {
        when()
                .get("/orders/price")
                .then()
                .statusCode(200)
                .body(is("9.99"));

        Target pricing = faults.target(PricingClient.class);
        assertTrue(pricing.traffic().connectionsSeen() >= 1, "the call did not go through the proxy");

        try (ActiveFault f = pricing.unavailable()) {
            when()
                    .get("/orders/price")
                    .then()
                    .statusCode(500);
        }

        when()
                .get("/orders/price")
                .then()
                .statusCode(200);
    }
}
