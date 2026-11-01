package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The client side of a test of the proxy: plain sockets to a proxy, and what to expect from them.
 */
public final class Sockets {

    /**
     * How long a read waits before a test gives up: long enough for anything the proxy relays, short enough that a
     * test of a hang does not stall the build.
     */
    public static final int READ_TIMEOUT_MILLIS = 2_000;

    /**
     * Something to time that may fail the way socket code fails.
     */
    public interface Call {
        void run() throws IOException;
    }

    private Sockets() {
    }

    /**
     * A connection to the proxy, with two seconds to connect and {@link #READ_TIMEOUT_MILLIS} to read.
     */
    public static Socket connect(ProxyTarget target) throws IOException {
        return connect(target, 2_000);
    }

    public static Socket connect(ProxyTarget target, int connectTimeoutMillis) throws IOException {
        Socket s = new Socket();
        s.setSoTimeout(READ_TIMEOUT_MILLIS);
        s.connect(new InetSocketAddress("127.0.0.1", target.port()), connectTimeoutMillis);
        return s;
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static String readExactly(InputStream in, int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        assertEquals(n, buf.length, "stream ended early: " + new String(buf, StandardCharsets.US_ASCII));
        return new String(buf, StandardCharsets.US_ASCII);
    }

    /**
     * The status line and headers of a response, up to and including the blank line that ends them.
     */
    public static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            assertTrue(b >= 0, "stream ended in the headers: " + head);
            head.append((char) b);
        }
        return head.toString();
    }

    /**
     * How long a call took, in milliseconds.
     */
    public static long millis(Call call) throws IOException {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * The next read fails with a TCP reset: not a clean end of stream, not a timeout.
     */
    public static void expectReset(InputStream in) {
        IOException e = assertThrows(IOException.class, () -> {
            if (in.read() < 0) {
                throw new IOException("closed cleanly instead of reset");
            }
        });
        assertTrue(e.getMessage().toLowerCase().contains("reset"), "expected a reset, got: " + e);
    }
}
