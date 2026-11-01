package io.quarkiverse.resiliencytesting.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsRuntime;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.Upstream;

class FaultsJsonRPCServiceTest {

    @Test
    void anHttpFaultOnATargetThatIsNotHttpLeavesItsFaultInPlace() {
        try {
            // no connection goes through this proxy, so its upstream is never dialled
            FaultsRuntime.registry().targetFor(TargetName.of("datasource", "users"), Upstream.of("localhost", 1),
                    "users.url");
            FaultsImpl faults = new FaultsImpl(true, List.of(), List.of(), List.of(), List.of());
            FaultsJsonRPCService service = new FaultsJsonRPCService(faults, new FaultHistory(faults));
            service.apply("datasource:users", "hangs", null, null, null, null);
            String before = faults.byName("datasource:users").currentFault();

            for (String fault : List.of("responds-with:503", "dies-mid-request:headers", "closes:chunks:1",
                    "hangs:sse:2")) {
                UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                        () -> service.apply("datasource:users", fault, null, null, null, null));
                assertTrue(e.getMessage().contains("plaintext HTTP/1.x"), e.getMessage());
                assertTrue(e.getMessage().contains("datasource:users (users.url)"), e.getMessage());
                assertEquals(before, faults.byName("datasource:users").currentFault());
            }
        } finally {
            FaultsRuntime.close();
        }
    }
}
