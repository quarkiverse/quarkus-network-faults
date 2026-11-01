package io.quarkiverse.resiliencytesting.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.Moment;

class FaultSpecTest {

    @Test
    void parsesEveryForm() {
        assertEquals("hangs", parse("hangs").kind());
        assertEquals(Moment.Http.afterSseEvents(3), parse("closes:sse:3").moment());
        assertEquals(Moment.afterBytes(512), parse("dies-mid-request:512").moment());
        assertEquals(Moment.onNextWrite(), parse("hangs:next-write").moment());
        assertEquals(Moment.Http.afterHeaders(), parse("hangs:headers").moment());
        assertEquals(Moment.Http.afterChunks(2), parse("dies-mid-request:chunks:2").moment());
        assertEquals(Duration.ofMillis(500), parse("slow:500ms").duration());
        assertEquals(503, parse("responds-with:503").status());
    }

    @Test
    void parsesAScopeAndAResponse() {
        FaultSpec.Parsed scoped = FaultSpec.parse(new FaultRequest("hangs", "POST /inventory/reserve", null, null));
        assertEquals("POST", scoped.scopeMethod());
        assertEquals("/inventory/reserve", scoped.scopePath());
        FaultSpec.Parsed response = FaultSpec.parse(new FaultRequest("responds-with:429", null, "{}", "application/json"));
        assertEquals(429, response.status());
        assertEquals("{}", response.body());
        assertEquals("application/json", response.contentType());
    }

    @Test
    void parsesHeadersOnePerLine() {
        FaultSpec.Parsed parsed = FaultSpec.parse(new FaultRequest("responds-with:429", null, null, null,
                "Retry-After: 30\n\nX-RateLimit-Remaining:0\n"));
        assertEquals(Map.of("Retry-After", "30", "X-RateLimit-Remaining", "0"), parsed.headers());
        assertTrue(FaultSpec.parse(new FaultRequest("responds-with:503", null, null, null, null)).headers().isEmpty());
    }

    @Test
    void rejectsBadInput() {
        assertMessage("expected \"METHOD /path\"", new FaultRequest("hangs", "POST", null, null));
        assertMessage("expected \"METHOD /path\"", new FaultRequest("hangs", "post /x", null, null));
        assertMessage("takes no request scope", new FaultRequest("unavailable", "POST /x", null, null));
        assertMessage("takes no request scope", new FaultRequest("connect-timeout", "POST /x", null, null));
        assertMessage("responds-with needs a status", new FaultRequest("responds-with", null, null, null));
        assertMessage("responds-with needs a status", new FaultRequest("responds-with:abc", null, null, null));
        assertMessage("only responds-with takes a body", new FaultRequest("hangs", null, "x", null));
        assertMessage("unknown fault", new FaultRequest("explodes", null, null, null));
        assertMessage("expected a \"Name: value\" header",
                new FaultRequest("responds-with:429", null, null, null, "Retry-After"));
        assertMessage("only responds-with takes", new FaultRequest("hangs", null, null, null, "Retry-After: 30"));
    }

    @Test
    void examplesCarryAMeaningAndIncludeRespondsWith() {
        boolean found = false;
        for (FaultSpec.Example e : FaultSpec.examples()) {
            assertFalse(e.meaning().isBlank());
            found |= e.spec().equals("responds-with:503");
        }
        assertTrue(found);
    }

    private static FaultSpec.Parsed parse(String spec) {
        return FaultSpec.parse(new FaultRequest(spec, null, null, null));
    }

    private static void assertMessage(String fragment, FaultRequest request) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FaultSpec.parse(request));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
