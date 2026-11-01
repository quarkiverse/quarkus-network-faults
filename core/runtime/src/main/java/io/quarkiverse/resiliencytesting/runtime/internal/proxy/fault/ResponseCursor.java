package io.quarkiverse.resiliencytesting.runtime.internal.proxy.fault;

import java.util.List;

import io.quarkiverse.resiliencytesting.Moment;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.HttpMark;
import io.vertx.core.buffer.Buffer;

/**
 * Where in a response a mid-request fault strikes, looked for on one connection in the downstream buffers in the
 * order they arrive. A byte count is counted here, whatever the protocol; the other positions are places in an
 * HTTP response, which arrive as the marks of each buffer.
 */
public abstract class ResponseCursor {

    /**
     * A cursor for {@code when}, which must not be {@link Moment.OnNextWrite}.
     */
    static ResponseCursor of(Moment when) {
        return switch (when) {
            case Moment.AfterBytes(long bytes) -> new ByteCount(bytes);
            case Moment.Http.AfterHeaders() -> new Mark(HttpMark.Kind.RESPONSE_HEADERS, 0);
            case Moment.Http.AfterChunks(int chunks) -> new Mark(HttpMark.Kind.CHUNK, chunks);
            case Moment.Http.AfterSseEvents(int events) -> new Mark(HttpMark.Kind.SSE_EVENT, events);
            case Moment.OnNextWrite() -> throw new IllegalArgumentException("not a position in a response: " + when);
        };
    }

    /**
     * Whether the position is a place in an HTTP response, which only a plaintext HTTP target has.
     */
    public static boolean readsHttp(Moment when) {
        return switch (when) {
            case Moment.AfterBytes bytes -> false;
            case Moment.OnNextWrite write -> false;
            case Moment.Http.AfterHeaders headers -> true;
            case Moment.Http.AfterChunks chunks -> true;
            case Moment.Http.AfterSseEvents events -> true;
        };
    }

    /**
     * The position in words, for a fault's description.
     */
    static String describe(Moment when) {
        return switch (when) {
            case Moment.AfterBytes(long bytes) -> "after " + bytes + " response byte(s)";
            case Moment.OnNextWrite() -> "on next write";
            case Moment.Http.AfterHeaders() -> "after the response headers";
            case Moment.Http.AfterChunks(int chunks) -> "after " + chunks + " chunk(s)";
            case Moment.Http.AfterSseEvents(int events) -> "after " + events + " SSE event(s)";
        };
    }

    /**
     * Looks at the next downstream buffer and its marks. Returns the offset in the buffer just past the position,
     * {@code 0} when the position was reached before this buffer, or {@code -1} when it lies beyond this buffer.
     */
    abstract int scan(Buffer data, List<HttpMark> marks);

    private static final class ByteCount extends ResponseCursor {

        private long remaining;

        ByteCount(long bytes) {
            this.remaining = bytes;
        }

        @Override
        int scan(Buffer data, List<HttpMark> marks) {
            if (remaining <= 0) {
                return 0;
            }
            if (data.length() < remaining) {
                remaining -= data.length();
                return -1;
            }
            int cut = (int) remaining;
            remaining = 0;
            return cut;
        }
    }

    /**
     * The first mark of one kind and count.
     */
    private static final class Mark extends ResponseCursor {

        private final HttpMark.Kind kind;
        private final int count;
        private boolean reached;

        Mark(HttpMark.Kind kind, int count) {
            this.kind = kind;
            this.count = count;
        }

        @Override
        int scan(Buffer data, List<HttpMark> marks) {
            if (reached) {
                return 0;
            }
            for (HttpMark mark : marks) {
                if (mark.kind() == kind && mark.count() == count) {
                    reached = true;
                    return mark.offset();
                }
            }
            return -1;
        }
    }
}
