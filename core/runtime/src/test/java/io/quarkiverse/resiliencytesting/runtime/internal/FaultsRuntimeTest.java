package io.quarkiverse.resiliencytesting.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkiverse.resiliencytesting.runtime.internal.config.TargetCatalog;
import io.quarkiverse.resiliencytesting.spi.TargetName;

class FaultsRuntimeTest {

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void rewriteLogsDoNotExposeEndpointValues(boolean supported) {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(FaultsRuntime.class.getName());
        Level previousLevel = logger.getLevel();
        PatternFormatter formatter = new PatternFormatter("%s");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(formatter.format(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try {
            String value = "https://user:private-password@remote/path?token=private-token";
            String rewritten = value.replace("remote", "127.0.0.1:12345");
            String property = "test.endpoint.url";
            var match = new TargetCatalog.Match((v, context) -> supported ? rewritten : null,
                    TargetName.of("test"), property);
            assertEquals(supported ? rewritten : value, FaultsRuntime.rewrite(match, value, null));
            assertEquals(1, messages.size());
            String message = messages.getFirst();
            assertTrue(message.contains(property), message);
            assertTrue(message.contains(supported ? "now points at" : "unsupported form"), message);
            assertFalse(message.contains("private-password"), message);
            assertFalse(message.contains("private-token"), message);
            assertFalse(message.contains(value), message);
            assertFalse(message.contains(rewritten), message);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            FaultsRuntime.close();
        }
    }
}
