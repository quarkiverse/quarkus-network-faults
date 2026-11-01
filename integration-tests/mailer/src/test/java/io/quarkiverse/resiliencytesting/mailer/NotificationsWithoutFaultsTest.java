package io.quarkiverse.resiliencytesting.mailer;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(SmtpServerStub.class)
public class NotificationsWithoutFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void nothingIsProxied() {
        assertTrue(faults.targetNames().isEmpty(), faults.targetNames().toString());
    }

    @Test
    public void mailsAreSent() {
        when()
                .post("/notifications/order")
                .then()
                .statusCode(204);

        when()
                .post("/notifications/alert")
                .then()
                .statusCode(204);
    }
}
