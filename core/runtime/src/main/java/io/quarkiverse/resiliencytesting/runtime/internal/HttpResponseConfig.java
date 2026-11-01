package io.quarkiverse.resiliencytesting.runtime.internal;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.HttpResponse;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyRegistry;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault.HttpResponseFault;

/**
 * The {@link HttpResponse} handed to a test for an applied {@code respondsWith} fault: it refines the response on
 * the proxy's event loop, where the fault reads it, and ends the fault.
 */
final class HttpResponseConfig implements HttpResponse {

    private final ProxyRegistry registry;
    private final HttpResponseFault fault;
    private final ActiveFault applied;

    HttpResponseConfig(ProxyRegistry registry, HttpResponseFault fault, ActiveFault applied) {
        this.registry = registry;
        this.fault = fault;
        this.applied = applied;
    }

    @Override
    public HttpResponse body(String body) {
        registry.run(() -> {
            fault.setBody(body);
        });
        return this;
    }

    @Override
    public HttpResponse bodyOfLength(long bytes) {
        registry.run(() -> {
            fault.setBodyOfLength(bytes);
        });
        return this;
    }

    @Override
    public HttpResponse contentType(String contentType) {
        registry.run(() -> {
            fault.setContentType(contentType);
        });
        return this;
    }

    @Override
    public HttpResponse header(String name, String value) {
        registry.run(() -> {
            fault.addHeader(name, value);
        });
        return this;
    }

    @Override
    public void end() {
        applied.end();
    }

    /**
     * The fault this response refines, as the tracker handed it out.
     */
    ActiveFault applied() {
        return applied;
    }
}
