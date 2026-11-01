package io.quarkiverse.resiliencytesting.mailer;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.junit.Stopwatch;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MailerName;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The default mailer and a named one send to the SMTP stub through the proxy; each is addressed by the mailer it
 * injects.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
@QuarkusTestResource(SmtpServerStub.class)
public class MailerFaultsTest {

    @Inject
    Faults faults;

    private Target mailer() {
        return faults.target(Mailer.class);
    }

    private Target alerts() {
        return faults.target(Mailer.class, MailerName.Literal.of("alerts"));
    }

    @Test
    public void everyMailerIsATarget() {
        assertEquals("mailer:<default>", mailer().name());
        assertEquals("mailer:alerts", alerts().name());
        assertEquals(Set.of("mailer:<default>", "mailer:alerts"), faults.targetNames());
    }

    @Test
    public void mailerUnavailable() {
        when()
                .post("/notifications/order")
                .then()
                .statusCode(204);

        try (ActiveFault f = mailer().unavailable()) {
            when()
                    .post("/notifications/order")
                    .then()
                    .statusCode(503);
        }

        when()
                .post("/notifications/order")
                .then()
                .statusCode(204);
    }

    @Test
    public void mailerHangsIsBoundedByTheTimeout() {
        when()
                .post("/notifications/order")
                .then()
                .statusCode(204);

        try (ActiveFault f = mailer().hangs()) {
            long ms = Stopwatch.millis(() -> when()
                    .post("/notifications/order")
                    .then()
                    .statusCode(503));
            assertTrue(ms >= 1_500 && ms < 4_000, "expected quarkus.mailer.timeout=2s to bound the wait, took " + ms + "ms");
        }

        when()
                .post("/notifications/order")
                .then()
                .statusCode(204);
    }

    @Test
    public void aNamedMailerFailsOnItsOwn() {
        try (ActiveFault f = alerts().unavailable()) {
            when()
                    .post("/notifications/alert")
                    .then()
                    .statusCode(503);

            when()
                    .post("/notifications/order")
                    .then()
                    .statusCode(204);
        }
    }
}
