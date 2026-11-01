package io.quarkiverse.resiliencytesting.spi;

/**
 * One upstream address found in a configuration value.
 *
 * @param http whether the upstream speaks plaintext HTTP/1.x, so that the proxy can read what passes through it:
 *        requests are counted, and the faults that act on the HTTP exchange can be applied
 */
public record Upstream(String host, int port, boolean http) {

    /**
     * An upstream whose protocol the proxy only relays.
     */
    public static Upstream of(String host, int port) {
        return new Upstream(host, port, false);
    }

    /**
     * An upstream that speaks plaintext HTTP/1.x.
     */
    public static Upstream http(String host, int port) {
        return new Upstream(host, port, true);
    }
}
