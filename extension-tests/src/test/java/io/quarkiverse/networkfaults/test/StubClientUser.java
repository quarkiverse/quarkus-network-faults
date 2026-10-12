package io.quarkiverse.networkfaults.test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;

/**
 * Reads the stub client's url at startup, so the target exists before the Dev UI asks for it.
 */
@ApplicationScoped
public class StubClientUser {

    void onStart(@Observes StartupEvent event) {
        ConfigProvider.getConfig().getValue("quarkus.rest-client.stub.url", String.class);
    }
}
