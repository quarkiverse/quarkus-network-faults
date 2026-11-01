package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The requests a fault acts on, and the requests an endpoint's traffic counts: every request, for a whole target; the
 * requests with a method and a path, or in each of several such selections; and of those, every one or the first
 * {@code n}, every {@code n}th or the ones after the first {@code n}, counted as they arrive. A path is a template as
 * {@code @Path} writes them and matches the end of a request's path, so that a base path in the client's URL does not
 * have to be repeated; the query string is not compared. A path is a template as {@code @Path} writes them
 * and
 * matches the end of a request's path, so that a base path in the client's URL does not have to be repeated; the
 * query string is not compared.
 */
public final class RequestScope {

    /**
     * Every request, and whatever goes through a target that is not a request: the scope of a whole target.
     */
    public static final RequestScope ALL = new RequestScope(List.of(), null, 0);

    private record Selection(String method, String template, Pattern path) {
    }

    // a request is in the scope when it is in every one of them
    private final List<Selection> selections;
    // which of those requests, counted as they arrive, the scope takes; null for all of them
    private final RequestCounter.Kind counting;
    private final int count;

    private RequestScope(List<Selection> selections, RequestCounter.Kind counting, int count) {
        this.selections = selections;
        this.counting = counting;
        this.count = count;
    }

    /**
     * The requests that are in this scope and also have this method and path.
     */
    public RequestScope and(String method, String path) {
        List<Selection> both = new ArrayList<>(selections);
        both.add(selection(method, path));
        return new RequestScope(List.copyOf(both), counting, count);
    }

    /**
     * The first {@code n} of the requests in this scope.
     */
    public RequestScope first(int n) {
        return counting(RequestCounter.Kind.FIRST, n);
    }

    /**
     * Every {@code n}th of the requests in this scope.
     */
    public RequestScope every(int n) {
        return counting(RequestCounter.Kind.EVERY, n);
    }

    /**
     * The requests in this scope after the first {@code n}.
     */
    public RequestScope after(int n) {
        return counting(RequestCounter.Kind.AFTER, n);
    }

    private RequestScope counting(RequestCounter.Kind kind, int n) {
        if (counting != null) {
            throw new IllegalStateException("The endpoint already selects requests by count, " + this
                    + ": forFirst, every and after do not combine.");
        }
        RequestCounter.check(kind, n);
        return new RequestScope(selections, kind, n);
    }

    /**
     * A fresh counter of the requests this scope takes, or null when it takes every request it covers. Each applied
     * fault counts for itself, across every proxy it was applied to.
     */
    public RequestCounter newCounter() {
        return counting == null ? null : new RequestCounter(counting, count);
    }

    public boolean isAll() {
        return selections.isEmpty() && counting == null;
    }

    /**
     * Whether the exchange, null for what is not a request, is in the scope.
     */
    public boolean covers(HttpExchange exchange) {
        if (isAll()) {
            return true;
        }
        return exchange != null && covers(exchange.method(), exchange.path());
    }

    /**
     * Whether a request with this method and path, the query string left out, is in the scope.
     */
    public boolean covers(String method, String path) {
        for (Selection s : selections) {
            if (!s.method().equals(method) || !s.path().matcher(path).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A fault on this scope in words: the fault, followed by the requests it acts on unless it acts on all of them.
     */
    public String describe(Fault fault) {
        return isAll() ? fault.describe() : fault.describe() + " for " + this;
    }

    /**
     * Two scopes are equal when they select the same requests: the same methods and paths in the same order, and the
     * same count.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof RequestScope other && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public String toString() {
        if (isAll()) {
            return "every request";
        }
        StringJoiner joined = new StringJoiner(" and ");
        for (Selection s : selections) {
            joined.add(s.method() + " " + s.template());
        }
        if (counting == null) {
            return joined.toString();
        }
        String counted = RequestCounter.describe(counting, count);
        return selections.isEmpty() ? counted : counted + " " + joined;
    }

    private static Selection selection(String method, String path) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("an HTTP method is needed, such as GET or POST");
        }
        String template = path.startsWith("/") ? path : "/" + path;
        return new Selection(method.trim().toUpperCase(Locale.ROOT), template, pattern(template));
    }

    /**
     * Anything in front of the template, which is a base path; a parameter is one segment, or what its regular
     * expression matches; a trailing slash makes no difference.
     */
    private static Pattern pattern(String template) {
        String path = template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        StringBuilder regex = new StringBuilder(".*");
        int i = 0;
        while (i < path.length()) {
            int open = path.indexOf('{', i);
            if (open < 0) {
                regex.append(Pattern.quote(path.substring(i)));
                break;
            }
            regex.append(Pattern.quote(path.substring(i, open)));
            int close = closingBrace(path, open);
            String parameter = path.substring(open + 1, close);
            int colon = parameter.indexOf(':');
            regex.append(colon < 0 ? "[^/]+" : "(?:" + parameter.substring(colon + 1).trim() + ")");
            i = close + 1;
        }
        return Pattern.compile(regex.append("/?").toString());
    }

    /**
     * Where the parameter that opens at {@code open} closes; its regular expression may have braces of its own.
     */
    private static int closingBrace(String path, int open) {
        int depth = 0;
        for (int i = open; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unclosed '{' in the path " + path);
    }
}
