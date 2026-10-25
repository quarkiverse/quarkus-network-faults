package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.Moment;
import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.Target;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class PaymentFailuresTest {

    @Inject
    NetworkFaults faults;

    @Inject
    PaymentLedger ledger;

    @Test
    void responseLostAfterThePaymentWentThrough() {
        Target payments = faults.target(PaymentClient.class);
        try (var ignored = payments.http().forFirst(1)
                .diesMidRequest(Moment.Http.afterHeaders())) {
            when()
                    .post("/orders/42/payment")
                    .then()
                    .statusCode(200);
        }

        var attempts = payments.traffic().httpRequests();
        assertEquals(2, attempts.size());
        assertEquals(200, attempts.get(0).responseStatus());
        assertEquals(1, ledger.chargesFor("42"));
    }
}
