package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

/**
 * Counts the requests a {@link RequestScope} covers as they arrive and tells which of them the scope takes: the first
 * {@code n}, every {@code n}th, or every one after the first {@code n}. One per applied fault, on the event loop.
 */
public final class RequestCounter {

    enum Kind {
        FIRST,
        EVERY,
        AFTER
    }

    private final Kind kind;
    private final int n;
    private long seen;

    RequestCounter(Kind kind, int n) {
        this.kind = kind;
        this.n = n;
    }

    static void check(Kind kind, int n) {
        if (kind == Kind.EVERY && n < 1) {
            throw new IllegalArgumentException("every(n) needs n >= 1, got " + n);
        }
        if (n < 0) {
            throw new IllegalArgumentException("a request count cannot be negative, got " + n);
        }
    }

    /**
     * Counts the next request and tells whether the scope takes it.
     */
    boolean next() {
        seen++;
        return switch (kind) {
            case FIRST -> seen <= n;
            case EVERY -> (seen - 1) % n == 0;
            case AFTER -> seen > n;
        };
    }

    /**
     * The selection in words, in front of what it selects from: "first 2", "every 3rd", "after the first 2".
     */
    static String describe(Kind kind, int n) {
        return switch (kind) {
            case FIRST -> "first " + n;
            case EVERY -> "every " + n + ordinal(n);
            case AFTER -> "after the first " + n;
        };
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return "th";
        }
        return switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
