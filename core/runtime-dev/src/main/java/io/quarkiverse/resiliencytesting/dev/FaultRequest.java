package io.quarkiverse.resiliencytesting.dev;

/**
 * A fault as the Dev UI sends it.
 *
 * @param spec the fault, as {@link FaultSpec} describes it
 * @param requests {@code "METHOD /path"} to fault only those requests, or null for every request
 * @param body the response body, only meaningful with {@code responds-with} and null otherwise
 * @param contentType the response content type, only meaningful with {@code responds-with} and null otherwise
 * @param headers extra response headers, one {@code Name: value} per line, only meaningful with
 *        {@code responds-with} and null otherwise
 */
record FaultRequest(String spec, String requests, String body, String contentType, String headers) {

    FaultRequest(String spec, String requests, String body, String contentType) {
        this(spec, requests, body, contentType, null);
    }
}
