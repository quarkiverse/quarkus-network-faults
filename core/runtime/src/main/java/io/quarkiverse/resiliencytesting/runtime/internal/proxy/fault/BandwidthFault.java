package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.vertx.core.buffer.Buffer;

/**
 * Throttles throughput: each buffer is released once the byte budget allows, so bursts are spread evenly.
 */
public final class BandwidthFault extends DirectionalFault {

    private final long bytesPerSecond;

    public BandwidthFault(long bytesPerSecond, Advanced.Direction direction) {
        super(direction);
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("bytesPerSecond must be > 0");
        }
        this.bytesPerSecond = bytesPerSecond;
    }

    @Override
    public String describe() {
        return "bandwidth " + bytesPerSecond + " B/s" + directionSuffix();
    }

    @Override
    public void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        if (!applies(pipe.direction())) {
            pipe.forward(data);
            return;
        }
        pipe.forwardSpaced(data, data.length() * TimeUnit.SECONDS.toNanos(1) / bytesPerSecond);
    }
}
