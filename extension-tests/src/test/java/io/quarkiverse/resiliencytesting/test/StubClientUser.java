package io.quarkiverse.resiliencytesting.test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;

/**
 * Reads the stub clients' urls at startup, so the targets exist before the Dev UI asks for them.
 */
@ApplicationScoped
public class StubClientUser {

    void onStart(@Observes StartupEvent event) {
        ConfigProvider.getConfig().getValue("quarkus.rest-client.stub.url", String.class);
        ConfigProvider.getConfig().getValue("quarkus.rest-client.secure.url", String.class);
    }
}
