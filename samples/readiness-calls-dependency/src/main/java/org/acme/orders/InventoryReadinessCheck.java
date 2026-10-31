package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * The bug: a readiness check that asks the inventory service whether it is up. Readiness answers "should traffic
 * be routed to this pod?", and the pod can serve everything that does not need the inventory, and answer honestly
 * for what does. When the inventory is down, every pod of this service fails the same check at the same time, the
 * balancer has nowhere left to route, and an outage of one dependency is an outage of the whole service.
 * <p>
 * The inventory's own readiness is the inventory's to report.
 */
@Readiness
@ApplicationScoped
@UnlessBuildProperty(name = "orders.inventory-readiness", stringValue = "false", enableIfMissing = true)
public class InventoryReadinessCheck implements HealthCheck {

    private final InventoryClient inventory;

    public InventoryReadinessCheck(@RestClient InventoryClient inventory) {
        this.inventory = inventory;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            inventory.status();
            return HealthCheckResponse.up("inventory");
        } catch (ProcessingException e) {
            return HealthCheckResponse.down("inventory");
        }
    }
}
