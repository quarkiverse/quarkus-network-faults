package io.quarkiverse.resiliencytesting.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The name of a target: a family, the technology it belongs to, and the segments that single one connection out
 * within it, for example {@code datasource}, {@code users}, {@code jdbc}. A name covers every name that starts
 * with its segments, so {@code datasource} is every datasource connection, {@code datasource:users} both
 * connections of one datasource, and {@code datasource:users:jdbc} exactly one. Written out, a name is
 * {@code family:segment:segment}, which is how the Dev UI shows it.
 * <p>
 * A segment may be a placeholder, {@code {host}} or {@code {port}}, filled in from the address once it is known,
 * which is how a list of brokers becomes one target per broker; a segment may also name a placeholder of the
 * property template it was declared with.
 * <p>
 * A proxy fronts one upstream address, and two addresses can end up under one name: the properties that configure
 * one REST Client's URL deliberately share a name, and a test may configure one of them with another
 * address. The second target then becomes {@code name#2}, its {@link #instance}, and the plain name covers it, so
 * a fault on the client reaches both.
 *
 * @param family the technology, {@code datasource}, {@code rest-client}, {@code kafka}
 * @param segments what singles the target out within the family; may be empty for a family as a whole
 * @param instance {@code 0} for the name itself, {@code 2} and up for a further address under the same name
 */
public record TargetName(String family, List<String> segments, int instance) {

    /**
     * The placeholders a segment may hold for the address the target stands for, {@code {host}} and {@code {port}}.
     */
    public static final String HOST = "host";
    public static final String PORT = "port";

    /**
     * Covers every target.
     */
    public static final TargetName ALL = new TargetName("all", List.of(), 0);

    public TargetName {
        check(family, "family");
        for (String segment : segments) {
            check(segment, "segment");
        }
        segments = List.copyOf(segments);
        if (instance < 0 || instance == 1) {
            throw new IllegalArgumentException("instance must be 0 or at least 2: " + instance);
        }
    }

    private static void check(String part, String what) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException("a target name's " + what + " must not be blank");
        }
        if (part.indexOf('#') >= 0 || (part.indexOf(':') >= 0 && !part.startsWith("["))) {
            throw new IllegalArgumentException("a target name's " + what + " must not contain ':' or '#': " + part);
        }
    }

    /**
     * A name from its family and segments, in that order.
     */
    public static TargetName of(String family, String... segments) {
        return new TargetName(family, List.of(segments), 0);
    }

    /**
     * This name as its {@code n}th instance: a further address under the same name.
     */
    public TargetName withInstance(int n) {
        return new TargetName(family, segments, n);
    }

    /**
     * Whether {@code other} is this name, an instance of it, or a name beneath it.
     */
    public boolean covers(TargetName other) {
        if (equals(ALL) || equals(other)) {
            return true;
        }
        if (!family.equals(other.family) || other.segments.size() < segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(other.segments.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same name with every segment that is a {@code {placeholder}} of {@code values} replaced by its value.
     */
    public TargetName substitute(Map<String, String> values) {
        List<String> out = new ArrayList<>(segments.size());
        for (String segment : segments) {
            String value = null;
            if (segment.startsWith("{") && segment.endsWith("}")) {
                value = values.get(segment.substring(1, segment.length() - 1));
            }
            out.add(value == null ? segment : value);
        }
        return new TargetName(family, out, instance);
    }

    /**
     * The name written out, {@code family:segment:segment}, with {@code #n} for an instance.
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(family);
        for (String segment : segments) {
            out.append(':').append(segment);
        }
        if (instance > 0) {
            out.append('#').append(instance);
        }
        return out.toString();
    }

    /**
     * The reverse of {@link #toString}; {@code all} is {@link #ALL}. A bracketed IPv6 host stays one segment.
     */
    public static TargetName parse(String text) {
        String s = text.trim();
        int instance = 0;
        int hash = s.lastIndexOf('#');
        if (hash >= 0) {
            instance = Integer.parseInt(s.substring(hash + 1));
            s = s.substring(0, hash);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean bracketed = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                bracketed = true;
            } else if (c == ']') {
                bracketed = false;
            }
            if (c == ':' && !bracketed) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        if (parts.size() == 1 && parts.getFirst().equals(ALL.family)) {
            return ALL;
        }
        return new TargetName(parts.getFirst(), parts.subList(1, parts.size()), instance);
    }
}
