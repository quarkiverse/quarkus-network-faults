package io.quarkiverse.resiliencytesting.dev;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Endpoint;
import io.quarkiverse.resiliencytesting.HttpResponse;
import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault.ResponseCursor;
import io.quarkus.runtime.configuration.DurationConverter;

/**
 * Textual form of a fault, as the Dev UI sends it: {@code unavailable}, {@code connect-timeout}, {@code hangs},
 * {@code slow:<duration>}, {@code dies-mid-request}, {@code closes}, and {@code dies-mid-request:<moment>},
 * {@code closes:<moment>} or {@code hangs:<moment>} where the moment is {@code <bytes>}, {@code next-write}, {@code headers},
 * {@code chunks:<n>} or {@code sse:<n>}. {@code responds-with:<status>} answers every request with that status. Every
 * fault but {@code unavailable} and {@code connect-timeout} may be scoped to {@code METHOD /path} requests.
 */
final class FaultSpec {

    /**
     * What a fault, parsed from a {@link FaultRequest}, consists of. Fields that do not apply to the kind are null.
     */
    record Parsed(String kind, String scopeMethod, String scopePath, Moment moment, Duration duration, Integer status,
            String body, String contentType, Map<String, String> headers) {

        /**
         * Whether the fault reads the HTTP exchange, so needs a target reached over plaintext HTTP, as the API decides
         * it.
         */
        boolean readsHttp() {
            return status != null || (moment != null && ResponseCursor.readsHttp(moment));
        }
    }

    /**
     * An accepted form of {@link FaultRequest#spec()}, what it does, whether it needs a plaintext HTTP target and
     * whether it is a {@code responds-with}, which takes a body and a content type. What {@code getFaults} returns.
     */
    public record Example(String spec, String meaning, boolean readsHttp, boolean respondsWith) {
    }

    private FaultSpec() {
    }

    /**
     * Every accepted form, with a sample argument where one is needed.
     */
    static List<Example> examples() {
        return List.of(
                example("unavailable", "connections are refused"),
                example("connect-timeout", "connecting never completes"),
                example("hangs", "the connection is accepted but nothing is ever answered"),
                example("slow:10s", "every byte is delayed, so the call takes that long"),
                example("dies-mid-request", "the connection is reset while the request is in flight"),
                example("dies-mid-request:512", "the connection is reset after 512 bytes"),
                example("dies-mid-request:next-write", "the connection is reset on the next write"),
                example("dies-mid-request:headers", "the connection is reset after the response headers"),
                example("dies-mid-request:chunks:2", "the connection is reset after 2 response chunks"),
                example("dies-mid-request:sse:3", "the connection is reset after 3 server-sent events"),
                example("hangs:sse:3", "the response stalls after 3 server-sent events"),
                example("closes", "the connection is closed cleanly"),
                example("closes:sse:1", "the connection is closed cleanly after 1 server-sent event"),
                example("responds-with:503",
                        "every request is answered with that status instead of reaching the upstream"));
    }

    private static Example example(String spec, String meaning) {
        Parsed parsed = parse(new FaultRequest(spec, null, null, null));
        return new Example(spec, meaning, parsed.readsHttp(), parsed.status() != null);
    }

    /**
     * Parses the fault {@code request} names.
     *
     * @throws IllegalArgumentException if {@code request} is not one of the accepted forms
     */
    static Parsed parse(FaultRequest request) {
        String spec = request.spec();
        String s = spec.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        String kind = colon < 0 ? s : s.substring(0, colon);
        String arg = colon < 0 ? null : s.substring(colon + 1).trim();
        String scopeMethod = null;
        String scopePath = null;
        if (request.requests() != null) {
            String scope = request.requests().trim();
            int space = scope.indexOf(' ');
            scopeMethod = space < 0 ? "" : scope.substring(0, space);
            scopePath = space < 0 ? "" : scope.substring(space + 1).trim();
            if (!scopeMethod.matches("[A-Z]+") || scopePath.isEmpty()) {
                throw new IllegalArgumentException(
                        "expected \"METHOD /path\", for example \"POST /inventory/reserve\": " + request.requests());
            }
        }
        if (!kind.equals("responds-with")
                && (request.body() != null || request.contentType() != null || request.headers() != null)) {
            throw new IllegalArgumentException("only responds-with takes a body, a content type or headers: " + spec);
        }
        Map<String, String> headers = parseHeaders(request.headers());
        Moment moment = null;
        Duration duration = null;
        Integer status = null;
        switch (kind) {
            case "unavailable", "connect-timeout" -> {
                if (arg != null) {
                    throw new IllegalArgumentException("fault '" + kind + "' takes no argument: " + spec);
                }
                if (scopeMethod != null) {
                    throw new IllegalArgumentException("fault '" + kind + "' takes no request scope: " + request.requests());
                }
            }
            case "hangs", "dies-mid-request", "closes" -> {
                if (arg != null) {
                    moment = parseMoment(arg, spec);
                }
            }
            case "slow" -> {
                if (arg == null) {
                    throw new IllegalArgumentException("slow needs a duration, for example slow:10s: " + spec);
                }
                duration = parseDuration(arg);
            }
            case "responds-with" -> {
                try {
                    status = Integer.valueOf(arg);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "responds-with needs a status, for example responds-with:503: " + spec, e);
                }
            }
            default -> throw new IllegalArgumentException("unknown fault '" + kind + "' in '" + spec
                    + "'; expected one of " + examplesSpecs());
        }
        return new Parsed(kind, scopeMethod, scopePath, moment, duration, status, request.body(), request.contentType(),
                headers);
    }

    /**
     * One {@code Name: value} header per line; blank lines are skipped. Empty when there is none.
     */
    private static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null) {
            return headers;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon).trim();
            if (name.isEmpty() || name.contains(" ")) {
                throw new IllegalArgumentException(
                        "expected a \"Name: value\" header per line, for example \"Retry-After: 30\": " + line);
            }
            headers.put(name, line.substring(colon + 1).trim());
        }
        return headers;
    }

    /**
     * Applies {@code parsed} to {@code target}, or to the requests it is scoped to.
     */
    static ActiveFault apply(Parsed parsed, Target target) {
        Endpoint endpoint = target;
        if (parsed.scopeMethod() != null) {
            endpoint = target.http().requests(parsed.scopeMethod(), parsed.scopePath());
        }
        switch (parsed.kind()) {
            case "unavailable" -> {
                return target.unavailable();
            }
            case "connect-timeout" -> {
                return target.connectTimeout();
            }
            case "hangs" -> {
                return parsed.moment() == null ? endpoint.hangs() : endpoint.hangs(parsed.moment());
            }
            case "dies-mid-request" -> {
                return parsed.moment() == null ? endpoint.diesMidRequest() : endpoint.diesMidRequest(parsed.moment());
            }
            case "closes" -> {
                return parsed.moment() == null ? endpoint.closes() : endpoint.closes(parsed.moment());
            }
            case "slow" -> {
                return endpoint.slow(parsed.duration());
            }
            default -> {
                HttpResponse response = endpoint.http().respondsWith(parsed.status());
                if (parsed.contentType() != null) {
                    response.contentType(parsed.contentType());
                }
                if (parsed.body() != null) {
                    response.body(parsed.body());
                }
                for (Map.Entry<String, String> header : parsed.headers().entrySet()) {
                    response.header(header.getKey(), header.getValue());
                }
                return response;
            }
        }
    }

    private static List<String> examplesSpecs() {
        List<String> specs = new ArrayList<>();
        for (Example example : examples()) {
            specs.add(example.spec());
        }
        return specs;
    }

    /**
     * {@code <bytes>}, {@code next-write}, {@code headers}, {@code chunks:<n>} or {@code sse:<n>}.
     */
    private static Moment parseMoment(String arg, String spec) {
        if (arg.equals("next-write")) {
            return Moment.onNextWrite();
        }
        if (arg.equals("headers")) {
            return Moment.Http.afterHeaders();
        }
        if (arg.startsWith("chunks:")) {
            return Moment.Http.afterChunks(Integer.parseInt(arg.substring("chunks:".length()).trim()));
        }
        if (arg.startsWith("sse:")) {
            return Moment.Http.afterSseEvents(Integer.parseInt(arg.substring("sse:".length()).trim()));
        }
        try {
            return Moment.afterBytes(Long.parseLong(arg));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cannot parse moment '" + arg + "' in '" + spec
                    + "'; expected a byte count, next-write, headers, chunks:<n> or sse:<n>", e);
        }
    }

    /**
     * Quarkus' own duration syntax: {@code 10s}, {@code 500ms}, {@code 1h30m}, {@code PT10S}, plain seconds.
     */
    private static Duration parseDuration(String value) {
        try {
            return DurationConverter.parseDuration(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot parse duration '" + value + "' (use 10s, 500ms, 2m or PT10S)", e);
        }
    }
}
