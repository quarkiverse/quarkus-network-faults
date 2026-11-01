package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.vertx.core.buffer.Buffer;

/**
 * Delays every buffer by a fixed latency plus optional jitter: {@code slow(latency)}, and {@code advanced().latency}
 * with jitter or a direction; both describe themselves as {@code slow}.
 */
public final class LatencyFault extends DirectionalFault {

    private final long latencyNanos;
    private final long jitterNanos;

    public LatencyFault(Duration latency, Duration jitter, Advanced.Direction direction) {
        super(direction);
        this.latencyNanos = latency.toNanos();
        this.jitterNanos = jitter.toNanos();
    }

    @Override
    public String describe() {
        return "slow " + Duration.ofNanos(latencyNanos).toMillis() + "ms"
                + (jitterNanos > 0 ? " ±" + Duration.ofNanos(jitterNanos).toMillis() + "ms" : "") + directionSuffix();
    }

    @Override
    public void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        if (!applies(pipe.direction())) {
            pipe.forward(data);
            return;
        }
        long delay = latencyNanos;
        if (jitterNanos > 0) {
            delay += ThreadLocalRandom.current().nextLong(jitterNanos + 1);
        }
        pipe.forwardAfter(data, delay);
    }
}
