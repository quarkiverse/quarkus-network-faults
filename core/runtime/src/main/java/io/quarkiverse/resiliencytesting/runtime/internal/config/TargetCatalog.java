package io.quarkiverse.resiliencytesting.runtime.internal.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;

/**
 * The compiled set of {@link TargetDescriptor}s: names the target a configuration property belongs to. Runs on
 * every configuration lookup, so each descriptor is pre-filtered by its literal prefix before its pattern runs.
 */
public final class TargetCatalog {

    /**
     * The target of a property: its name, with the property's placeholders filled in, and how to rewrite it.
     */
    public record Match(AddressRewriter rewriter, TargetName targetName, String property) {
    }

    private static final Set<String> ADDRESS_PLACEHOLDERS = Set.of(TargetName.HOST, TargetName.PORT);

    // A quoted or unquoted segment, one capture group.
    private static final String SEGMENT = "(\"[^\"]+\"|[^.\"]+)";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /**
     * A descriptor ready to match: the regex group of each placeholder the name needs from the property.
     */
    private record Compiled(AddressRewriter rewriter, String prefix, Pattern pattern, TargetName name,
            Map<String, Integer> groups) implements Comparable<Compiled> {
        /**
         * Fewer placeholders first; the sort is stable, so declaration order breaks ties.
         */
        @Override
        public int compareTo(Compiled other) {
            return Integer.compare(groups.size(), other.groups.size());
        }
    }

    private final List<Compiled> entries;

    private TargetCatalog(List<Compiled> entries) {
        this.entries = entries;
    }

    public static TargetCatalog of(List<TargetDescriptor> descriptors) {
        List<Compiled> compiled = new ArrayList<>(descriptors.size());
        for (TargetDescriptor d : descriptors) {
            compiled.add(compile(d));
        }
        // a literal property wins over a template that needs placeholders to match it
        Collections.sort(compiled);
        return new TargetCatalog(compiled);
    }

    private static Compiled compile(TargetDescriptor d) {
        if (d.name().family().equals(TargetName.ALL.family())) {
            throw new IllegalArgumentException(
                    "'" + TargetName.ALL.family() + "' names every target and cannot be a family: " + d);
        }
        String property = canonical(d.property());
        StringBuilder regex = new StringBuilder("^");
        Map<String, Integer> groups = new HashMap<>();
        List<String> segments = segments(property);
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            Matcher m = PLACEHOLDER.matcher(segment);
            String dot = i == 0 ? "" : "\\.";
            if (m.matches()) {
                groups.put(m.group(1), groups.size() + 1);
                regex.append(dot).append(SEGMENT);
            } else {
                regex.append(dot).append(Pattern.quote(segment));
            }
        }
        regex.append("(?:\\[\\d+])?$");
        for (String segment : d.name().segments()) {
            Matcher m = PLACEHOLDER.matcher(segment);
            if (m.matches() && !ADDRESS_PLACEHOLDERS.contains(m.group(1)) && !groups.containsKey(m.group(1))) {
                throw new IllegalArgumentException("unknown placeholder " + segment + " in " + d);
            }
        }
        int brace = property.indexOf('{');
        String prefix = brace < 0 ? property : property.substring(0, brace);
        return new Compiled(rewriter(d), prefix, Pattern.compile(regex.toString()), d.name(), groups);
    }

    private static AddressRewriter rewriter(TargetDescriptor d) {
        try {
            return d.rewriter().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(d.rewriter().getName() + " needs a public no-argument constructor: " + d, e);
        }
    }

    // Quotes around a segment only matter when it contains dots, but SmallRye Config may spell a map key with them
    // either way ("quarkus.datasource.\"audit\".jdbc.url" for a datasource configured as "audit"), so descriptors
    // and looked-up names are both reduced to the spelling with only the quotes that are needed.
    static String canonical(String property) {
        if (property.indexOf('"') < 0) {
            return property;
        }
        StringBuilder out = new StringBuilder(property.length());
        int i = 0;
        while (i < property.length()) {
            char c = property.charAt(i);
            int close = c == '"' ? property.indexOf('"', i + 1) : -1;
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            String inner = property.substring(i + 1, close);
            out.append(inner.indexOf('.') < 0 ? inner : '"' + inner + '"');
            i = close + 1;
        }
        return out.toString();
    }

    // Splits a template on dots outside quotes.
    private static List<String> segments(String template) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : template.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == '.' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    public Match match(String property) {
        String name = canonical(property);
        for (Compiled c : entries) {
            if (!name.startsWith(c.prefix())) {
                continue;
            }
            Matcher m = c.pattern().matcher(name);
            if (m.matches()) {
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<String, Integer> group : c.groups().entrySet()) {
                    String value = m.group(group.getValue());
                    values.put(group.getKey(), value.charAt(0) == '"' ? value.substring(1, value.length() - 1) : value);
                }
                return new Match(c.rewriter(), c.name().substitute(values), property);
            }
        }
        return null;
    }
}
