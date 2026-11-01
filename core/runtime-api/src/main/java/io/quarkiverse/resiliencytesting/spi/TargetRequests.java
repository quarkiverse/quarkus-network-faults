package io.quarkiverse.resiliencytesting.spi;

/**
 * The requests a call to one method of a bean sends, as a {@link BeanTargetResolver} describes them.
 *
 * @param target the target they go to
 * @param method their HTTP method, such as {@code POST}
 * @param path their path below the base URL, a template as {@code @Path} writes them: {@code /inventory/{id}}
 */
public record TargetRequests(TargetName target, String method, String path) {
}
