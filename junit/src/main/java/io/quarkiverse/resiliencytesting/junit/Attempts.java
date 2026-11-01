package io.quarkiverse.resiliencytesting.junit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.quarkiverse.resiliencytesting.HttpRequestRecord;

/**
 * The attempts one call made at a dependency, as they show up in {@code Traffic.httpRequests()}: how many there were,
 * how far apart, and whether each carried the same idempotency key and body. The shape of a retry policy is what is
 * asserted, never exact durations, which do not survive a slow build machine: that the attempts were spaced out, that
 * the spacing varied, that the body did not change.
 */
public final class Attempts {

    private final List<HttpRequestRecord> records;

    private Attempts(List<HttpRequestRecord> records) {
        this.records = List.copyOf(records);
    }

    /**
     * The attempts in these records, oldest first.
     */
    public static Attempts of(List<HttpRequestRecord> records) {
        return new Attempts(records);
    }

    /**
     * The last {@code n} of these records, for the attempts of the call a test just made after everything that went
     * before it.
     */
    public static Attempts last(List<HttpRequestRecord> records, int n) {
        if (records.size() < n) {
            throw new AssertionError("Expected at least " + n + " request(s), found " + records.size() + ": " + records);
        }
        return new Attempts(records.subList(records.size() - n, records.size()));
    }

    public int count() {
        return records.size();
    }

    public List<HttpRequestRecord> records() {
        return records;
    }

    /**
     * The time between each attempt and the next, so one fewer than the attempts.
     */
    public List<Duration> gaps() {
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            gaps.add(Duration.between(records.get(i - 1).time(), records.get(i).time()));
        }
        return gaps;
    }

    /**
     * Every attempt waited at least this long after the one before: the retries were spaced out, not fired back to
     * back.
     */
    public Attempts assertGapsAtLeast(Duration minimum) {
        List<Duration> gaps = gaps();
        for (int i = 0; i < gaps.size(); i++) {
            if (gaps.get(i).compareTo(minimum) < 0) {
                throw new AssertionError("Attempt " + (i + 2) + " came " + gaps.get(i) + " after the one before, less "
                        + "than " + minimum + "; gaps: " + gaps);
            }
        }
        return this;
    }

    /**
     * The gaps are not all alike: the retry policy adds jitter, so clients do not come back in waves. Two gaps count
     * as alike when they differ by less than {@code tolerance}.
     */
    public Attempts assertGapsVary(Duration tolerance) {
        List<Duration> gaps = gaps();
        if (gaps.size() < 2) {
            throw new AssertionError("At least three attempts are needed to see whether their gaps vary; attempts: "
                    + records.size());
        }
        Duration first = gaps.getFirst();
        for (Duration gap : gaps) {
            if (gap.minus(first).abs().compareTo(tolerance) >= 0) {
                return this;
            }
        }
        throw new AssertionError("Every gap between attempts is within " + tolerance + " of " + first + ", so the "
                + "retries carry no jitter; gaps: " + gaps);
    }

    /**
     * Every attempt carried this header with one and the same value: the idempotency key a dependency needs to tell
     * a retry from a new request.
     */
    public Attempts assertSameHeader(String name) {
        Set<String> values = new LinkedHashSet<>();
        for (HttpRequestRecord r : records) {
            String value = r.header(name);
            if (value == null) {
                throw new AssertionError("Attempt on " + r.time() + " carried no " + name + " header: " + r);
            }
            values.add(value);
        }
        if (values.size() > 1) {
            throw new AssertionError("The attempts carried " + values.size() + " different values of " + name + ": "
                    + values);
        }
        return this;
    }

    /**
     * Every attempt sent the same body, as a retry must: a body that changes between attempts, a fresh timestamp for
     * instance, defeats a dependency's duplicate detection.
     */
    public Attempts assertSameBody() {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (HttpRequestRecord r : records) {
            if (r.bodyFingerprint() == null) {
                throw new AssertionError("Attempt on " + r.time() + " had no body the proxy read to the end: " + r);
            }
            fingerprints.add(r.bodyFingerprint());
        }
        if (fingerprints.size() > 1) {
            throw new AssertionError("The attempts sent " + fingerprints.size() + " different bodies: " + fingerprints);
        }
        return this;
    }
}
