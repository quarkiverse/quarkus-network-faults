package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A listening socket that never accepts, with its accept queue filled by the proxy's own connections. On Linux
 * (default {@code tcp_abort_on_overflow=0}) a SYN arriving at a full accept queue is dropped silently, so a client's
 * connect hangs until its own timeout. Plain JDK sockets: Vert.x cannot stop accepting on a listener. Not verified on
 * other operating systems, so refused there.
 */
final class BlackHole implements Closeable {

    /**
     * A connect on loopback completes at once, so one that has not after this long was dropped: the queue is full.
     */
    private static final int FILLER_CONNECT_TIMEOUT_MILLIS = 250;
    /**
     * Far more connections than an accept queue of one admits; needing them all means the queue is not filling.
     */
    private static final int MAX_FILLERS = 64;

    private final ServerSocket socket;
    private final List<Socket> fillers = new ArrayList<>();

    /**
     * Bind on {@code addr}; retries briefly because a Vert.x listener releases the port asynchronously.
     */
    static BlackHole open(InetSocketAddress addr) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            throw new UnsupportedOperationException(
                    "connectTimeout() relies on Linux dropping SYNs when the accept queue is full; not supported on " + os);
        }
        ServerSocket ss = bindWithRetry(addr);
        BlackHole hole = new BlackHole(ss);
        try {
            hole.fill(addr);
            return hole;
        } catch (IOException | RuntimeException e) {
            hole.close();
            throw e;
        }
    }

    private BlackHole(ServerSocket socket) {
        this.socket = socket;
    }

    private static ServerSocket bindWithRetry(InetSocketAddress addr) throws IOException {
        IOException last = null;
        long deadline = System.nanoTime() + Listener.REBIND_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(addr, 1); // the smallest accept queue there is, so that a few connections fill it
                return ss;
            } catch (IOException e) {
                ss.close();
                last = e;
                try {
                    Thread.sleep(Listener.REBIND_RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("could not bind the black hole on " + addr, last);
    }

    private void fill(InetSocketAddress addr) throws IOException {
        for (int i = 0; i < MAX_FILLERS; i++) {
            Socket s = new Socket();
            try {
                s.connect(addr, FILLER_CONNECT_TIMEOUT_MILLIS);
                fillers.add(s);
            } catch (SocketTimeoutException full) {
                s.close();
                return;
            } catch (IOException e) {
                s.close();
                throw e;
            }
        }
        throw new IOException("accept queue of " + addr + " did not fill after " + MAX_FILLERS + " connections");
    }

    @Override
    public void close() {
        for (Socket s : fillers) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        fillers.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
