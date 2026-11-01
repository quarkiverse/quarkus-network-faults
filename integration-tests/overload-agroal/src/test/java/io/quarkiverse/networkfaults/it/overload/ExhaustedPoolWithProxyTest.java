package io.quarkiverse.networkfaults.it.overload;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class ExhaustedPoolWithProxyTest extends ExhaustedPoolTest {

    // without the profile no resolver is asked, so the answer there is "not enabled" instead
    @Test
    void unknownBeanIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> faults.target(String.class));
    }
}
