package io.quarkiverse.resiliencytesting.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * A profile of its own, so the application the other profiled classes share is stopped and a fresh one started
 * before this class runs. The registry's instance count is a static shared across restarts: one means the previous
 * application closed its proxy when it shut down.
 */
@QuarkusTest
@TestProfile(RestartHygieneTest.OwnProfile.class)
@QuarkusTestResource(InventoryServer.class)
public class RestartHygieneTest {

    public static class OwnProfile extends NetworkFaultsProfile {
    }

    @Test
    public void thePreviousApplicationClosedItsProxy() {
        assertEquals(1, ProxyRegistry.liveInstances(), "one proxy Vert.x instance per application");
    }
}
