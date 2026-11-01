package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.time.Duration;
import java.util.List;

import io.quarkiverse.resiliencytesting.Advanced;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.Pipe;
import io.vertx.core.buffer.Buffer;

/**
 * Splits buffers into small chunks, each delayed a little more than the previous one, to shake out framing bugs.
 */
public final class SlicerFault extends DirectionalFault {

    private final int chunkSize;
    private final long delayNanos;

    public SlicerFault(int chunkSize, Duration delay, Advanced.Direction direction) {
        super(direction);
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        this.chunkSize = chunkSize;
        this.delayNanos = delay.toNanos();
    }

    @Override
    public String describe() {
        return "slice " + chunkSize + "B/" + Duration.ofNanos(delayNanos).toMillis() + "ms" + directionSuffix();
    }

    @Override
    public void onData(Pipe pipe, Buffer data, List<HttpMark> marks) {
        if (!applies(pipe.direction()) || data.length() <= chunkSize) {
            pipe.forward(data);
            return;
        }
        for (int offset = 0; offset < data.length(); offset += chunkSize) {
            Buffer chunk = data.slice(offset, Math.min(offset + chunkSize, data.length()));
            pipe.forwardSpaced(chunk, offset == 0 ? 0 : delayNanos);
        }
    }
}
