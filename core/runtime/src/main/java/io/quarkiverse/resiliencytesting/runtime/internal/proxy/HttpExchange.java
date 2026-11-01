package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

/**
 * One request and its response on a connection, as its request line names it: what a buffer belongs to, whether it
 * carries part of the request or part of the response.
 *
 * @param method the request's method, such as {@code GET}
 * @param target the request target, the path and the query string
 */
public record HttpExchange(String method, String target) {

    /**
     * The request target without its query string.
     */
    public String path() {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }
}
