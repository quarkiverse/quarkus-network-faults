package io.quarkiverse.resiliencytesting.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.HttpRequestRecord;

class AttemptsTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    private static HttpRequestRecord attempt(long millisAfterStart, String key, String body) {
        return new HttpRequestRecord(T0.plusMillis(millisAfterStart), 1, "POST", "/reserve",
                key == null ? Map.of() : Map.of("Idempotency-Key", List.of(key)), body, null, 200);
    }

    @Test
    void gapsAreBetweenConsecutiveAttempts() {
        Attempts attempts = Attempts.of(List.of(attempt(0, "k", "b"), attempt(250, "k", "b"), attempt(600, "k", "b")));
        assertEquals(3, attempts.count());
        assertEquals(List.of(Duration.ofMillis(250), Duration.ofMillis(350)), attempts.gaps());
    }

    @Test
    void lastTakesTheMostRecentAttempts() {
        List<HttpRequestRecord> all = List.of(attempt(0, "old", "b"), attempt(100, "k", "b"), attempt(300, "k", "b"));
        assertEquals(2, Attempts.last(all, 2).count());
        assertThrows(AssertionError.class, () -> Attempts.last(all, 4));
    }

    @Test
    void spacingAndJitterAreShapesNotDurations() {
        Attempts spaced = Attempts.of(List.of(attempt(0, "k", "b"), attempt(250, "k", "b"), attempt(530, "k", "b")));
        spaced.assertGapsAtLeast(Duration.ofMillis(200)).assertGapsVary(Duration.ofMillis(10));

        Attempts backToBack = Attempts.of(List.of(attempt(0, "k", "b"), attempt(5, "k", "b")));
        AssertionError tooClose = assertThrows(AssertionError.class,
                () -> backToBack.assertGapsAtLeast(Duration.ofMillis(200)));
        assertTrue(tooClose.getMessage().contains("Attempt 2"), tooClose.getMessage());

        Attempts regular = Attempts.of(List.of(attempt(0, "k", "b"), attempt(250, "k", "b"), attempt(500, "k", "b")));
        assertThrows(AssertionError.class, () -> regular.assertGapsVary(Duration.ofMillis(10)));
    }

    @Test
    void idempotencyKeyAndBodyMustNotChangeBetweenAttempts() {
        Attempts same = Attempts.of(List.of(attempt(0, "k", "b"), attempt(250, "k", "b")));
        same.assertSameHeader("idempotency-key").assertSameBody();

        Attempts newKey = Attempts.of(List.of(attempt(0, "k1", "b"), attempt(250, "k2", "b")));
        assertThrows(AssertionError.class, () -> newKey.assertSameHeader("Idempotency-Key"));
        Attempts noKey = Attempts.of(List.of(attempt(0, null, "b")));
        assertThrows(AssertionError.class, () -> noKey.assertSameHeader("Idempotency-Key"));
        Attempts newBody = Attempts.of(List.of(attempt(0, "k", "b1"), attempt(250, "k", "b2")));
        assertThrows(AssertionError.class, newBody::assertSameBody);
        Attempts noBody = Attempts.of(List.of(attempt(0, "k", null)));
        assertThrows(AssertionError.class, noBody::assertSameBody);
    }
}
