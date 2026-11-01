package org.acme.orders;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ShippingQuotes {

    private final ShippingClient shipping;

    public ShippingQuotes(@RestClient ShippingClient shipping) {
        this.shipping = shipping;
    }

    /**
     * A shipping quote. When the carrier says how long to wait before coming back, with a 429 or a 503 and a
     * Retry-After header, the call waits that long and tries once more.
     */
    public long quote(long orderId) {
        try {
            return shipping.quote(orderId);
        } catch (WebApplicationException e) {
            Duration wait = retryAfter(e.getResponse());
            if (wait == null) {
                throw e;
            }
            sleep(wait);
            return shipping.quote(orderId);
        }
    }

    private static Duration retryAfter(Response response) {
        int status = response.getStatus();
        String header = response.getHeaderString("Retry-After");
        if ((status != 429 && status != 503) || header == null) {
            return null;
        }
        return Duration.ofSeconds(Long.parseLong(header.trim()));
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
