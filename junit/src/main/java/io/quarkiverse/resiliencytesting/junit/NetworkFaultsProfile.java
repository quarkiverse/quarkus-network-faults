package io.quarkiverse.resiliencytesting.junit;

import java.util.Map;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Enables network fault injection for the test classes that use it:
 *
 * <pre>
 * &#64;QuarkusTest
 * &#64;TestProfile(NetworkFaultsProfile.class)
 * class OrderResourceTest { ... }
 * </pre>
 *
 * The flag is build-time configuration, and a test profile is what carries configuration into the build, so the
 * classes sharing this profile get one application built with every recognised outbound connection routed through
 * a proxy. Test classes without it are never proxied. A test class with a profile of its own extends this one, or
 * copies {@code quarkus.resiliency-testing.enabled=true} into its overrides.
 * <p>
 * After each test of such a class, {@link FaultsAfterEachCallback}, which this module registers for every
 * {@code @QuarkusTest}, ends every fault, undoes its consequences, and fails the test if a fault saw none of the
 * traffic it acts on, since it cannot have tested anything.
 */
public class NetworkFaultsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(Faults.ENABLED_PROPERTY, "true");
    }
}
