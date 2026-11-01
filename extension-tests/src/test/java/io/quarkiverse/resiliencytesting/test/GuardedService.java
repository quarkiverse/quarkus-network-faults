package io.quarkiverse.resiliencytesting.test;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Guarded by Fault Tolerance annotations on the class and on a method, so the inventory has something to list.
 */
@ApplicationScoped
@Retry(maxRetries = 2)
public class GuardedService {

    private final StubClient client;

    public GuardedService(@RestClient StubClient client) {
        this.client = client;
    }

    @Timeout(2000)
    @Fallback(fallbackMethod = "later")
    public String place() {
        return client.get();
    }

    public String later() {
        return "later";
    }

    private String helper() {
        return "";
    }

    public static String util() {
        return "";
    }
}
