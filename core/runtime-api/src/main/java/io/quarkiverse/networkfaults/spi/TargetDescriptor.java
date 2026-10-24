package io.quarkiverse.networkfaults.spi;

/**
 * Declares one configuration property that carries an upstream address. Extensions contribute these at build
 * time; the configuration interceptor proxies every property that matches one.
 *
 * @param property the property name, usually literal (the owning extension knows its datasources and clients
 *        at build time), or with {@code {x}} placeholders for name segments where it does not, for example
 *        {@code mp.messaging.outgoing.{channel}.bootstrap.servers}. Segments may be quoted in the template and in
 *        the actual property ({@code quarkus.datasource."my.ds".jdbc.url}, see {@link #propertySegment}). A
 *        trailing index ({@code [0]}) is accepted on every property. A literal property wins over templates that
 *        need placeholders to match it.
 * @param rewriter how the value carries its addresses: an {@link AddressRewriter} with a public no-argument
 *        constructor, {@link UrlRewriter} and {@link HostPortListRewriter} being the common ones
 * @param name the target's name; a segment may repeat a placeholder of the property, or be {@code {host}} or
 *        {@code {port}} to be filled in from each address in the value
 */
public record TargetDescriptor(String property, Class<? extends AddressRewriter> rewriter, TargetName name) {

    /**
     * Config key prefix for proxy ports reserved by a dev service customizer. A property
     * {@code quarkus.network-faults.reserved-port.<endpoint-property>} tells the interceptor to bind the proxy to
     * that port instead of an OS-allocated one, so the broker can advertise it.
     */
    public static final String RESERVED_PORT_PREFIX = "quarkus.network-faults.reserved-port.";

    /**
     * A configured name as one segment of a property name: quoted when it contains dots, as SmallRye Config
     * spells it ({@code quarkus.datasource."my.ds".jdbc.url}).
     */
    public static String propertySegment(String name) {
        return name.contains(".") ? '"' + name + '"' : name;
    }
}
