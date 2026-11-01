package io.quarkiverse.resiliencytesting.mailer;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

/**
 * A stand-in for an SMTP server that accepts every mail: no extensions, no authentication, no TLS.
 */
public class SmtpServerStub implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        NetServer server = vertx.createNetServer()
                .connectHandler(new Handler<NetSocket>() {
                    @Override
                    public void handle(NetSocket socket) {
                        socket.handler(RecordParser.newDelimited("\r\n", new Session(socket)));
                        socket.write("220 stub ESMTP\r\n");
                    }
                })
                .listen(0, "127.0.0.1").await();
        return Map.of("smtp.host", "127.0.0.1", "smtp.port", String.valueOf(server.actualPort()));
    }

    @Override
    public void stop() {
        vertx.close().await();
    }

    private static final class Session implements Handler<Buffer> {

        private final NetSocket socket;
        // between DATA and the line holding a single dot, lines are the mail itself
        private boolean data;

        Session(NetSocket socket) {
            this.socket = socket;
        }

        @Override
        public void handle(Buffer buffer) {
            String line = buffer.toString();
            if (data) {
                if (line.equals(".")) {
                    data = false;
                    reply("250 queued");
                }
                return;
            }
            String verb = line.length() < 4 ? line : line.substring(0, 4).toUpperCase();
            switch (verb) {
                case "DATA" -> {
                    data = true;
                    reply("354 end with <CRLF>.<CRLF>");
                }
                case "QUIT" -> {
                    reply("221 bye");
                    socket.close();
                }
                // EHLO, HELO, MAIL, RCPT, RSET, NOOP
                default -> reply("250 ok");
            }
        }

        private void reply(String line) {
            socket.write(line + "\r\n");
        }
    }
}
