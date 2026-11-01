package io.quarkiverse.resiliencytesting.it;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class InventoryService {

    private final InventoryClient client;
    private final FailureLog failures;

    public InventoryService(@RestClient InventoryClient client, FailureLog failures) {
        this.client = client;
        this.failures = failures;
    }

    @Timeout(1000)
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0, delay = 60_000)
    @Fallback(fallbackMethod = "unavailable")
    public String reserve(String item) {
        return client.reserve(item);
    }

    String unavailable(String item, Throwable cause) {
        failures.record(cause);
        return null;
    }

    @Retry(maxRetries = 8, delay = 250, jitter = 0)
    public String reserveWithRetry(String item) {
        return client.reserve(item);
    }
}
