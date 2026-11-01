package io.quarkiverse.resiliencytesting.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A value found at build time for an address property the application does not configure, such as a REST
 * Client's {@code @RegisterRestClient(baseUri)}. Served from a low-ordinal configuration source so the property
 * is proxied like a configured one; anything the application configures wins over it.
 */
public final class NetworkFaultDefaultValueBuildItem extends MultiBuildItem {

    private final String property;
    private final String value;

    public NetworkFaultDefaultValueBuildItem(String property, String value) {
        this.property = property;
        this.value = value;
    }

    public String property() {
        return property;
    }

    public String value() {
        return value;
    }
}
