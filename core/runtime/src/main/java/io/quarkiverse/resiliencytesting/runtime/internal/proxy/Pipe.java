package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Timer;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * One direction of a {@link ProxyConnection}: reads from {@code source}, writes to {@code sink}. All methods run on
 * the event loop.
 * <p>
 * Delayed writes go through a single FIFO queue per pipe drained by one timer, so bytes are never reordered
 * whatever delays a fault computes. Reading is paused (TCP backpressure) while the sink's write queue is full,
 * while a fault holds the pipe, or while too much is queued. A paused Vert.x socket delivers nothing until it
 * is resumed, so holding needs no buffer of its own.
 */
public final class Pipe {

    /**
     * How many delayed bytes a pipe queues before it stops reading from its source, and how far the queue has to
     * drain before it reads again. The gap keeps a slow fault from pausing and resuming on every buffer.
     */
    static final int HIGH_WATER = 1024 * 1024;
    static final int LOW_WATER = 256 * 1024;

    private record Delayed(Buffer data, long releaseAtNanos, Runnable onWritten) {
    }

    private final ProxyConnection connection;
    private final Direction direction;
    private final NetSocket source;
    private final NetSocket sink;
    private final ArrayDeque<Delayed> delayed = new ArrayDeque<>();
    private Timer drainTimer;
    private boolean drainQueued;
    private long delayedBytes;
    private long lastReleaseNanos;
    private boolean holding;
    private int pendingWrites;
    private Runnable whenDrained;

    Pipe(ProxyConnection connection, Direction direction, NetSocket source, NetSocket sink) {
        this.connection = connection;
        this.direction = direction;
        this.source = source;
        this.sink = sink;
    }

    public Direction direction() {
        return direction;
    }

    public ProxyConnection connection() {
        return connection;
    }

    void onData(Buffer data) {
        assert !holding : "data delivered while the source is paused";
        HttpConversation.Followed followed = connection.http().follow(direction, data);
        if (followed == null) {
            return; // the start of a request, which the conversation delivers with the rest of its request line
        }
        Fault fault = connection.target().routeTo(connection, direction, followed.exchange(), followed.starts());
        fault.onData(this, followed.data(), followed.marks());
    }

    /**
     * Write the buffer to the sink, behind anything still queued for it.
     */
    public void forward(Buffer data) {
        forward(data, null);
    }

    /**
     * Like {@link #forward(Buffer)}; {@code onWritten} runs once the bytes have been handed to the socket.
     */
    public void forward(Buffer data, Runnable onWritten) {
        if (!delayed.isEmpty()) {
            enqueue(data, lastReleaseNanos, onWritten);
            return;
        }
        writeNow(data, onWritten);
    }

    /**
     * Write the buffer {@code delayNanos} from now, never before anything queued earlier.
     */
    public void forwardAfter(Buffer data, long delayNanos) {
        enqueue(data, Math.max(System.nanoTime() + delayNanos, lastReleaseNanos), null);
    }

    /**
     * Write the buffer {@code gapNanos} after the previous delayed write (or after now, if the queue is idle).
     */
    public void forwardSpaced(Buffer data, long gapNanos) {
        enqueue(data, Math.max(System.nanoTime(), lastReleaseNanos) + gapNanos, null);
    }

    private void enqueue(Buffer data, long releaseAtNanos, Runnable onWritten) {
        lastReleaseNanos = releaseAtNanos;
        delayed.addLast(new Delayed(data, releaseAtNanos, onWritten));
        delayedBytes += data.length();
        pauseIfNeeded();
        if (drainTimer == null && !drainQueued) {
            scheduleDrain(releaseAtNanos - System.nanoTime());
        }
    }

    private void scheduleDrain(long inNanos) {
        if (inNanos <= 0) {
            drainQueued = true;
            connection.vertx().runOnContext(v -> drain());
        } else {
            drainTimer = connection.vertx().timer(inNanos, TimeUnit.NANOSECONDS);
            drainTimer.onSuccess(v -> drain());
        }
    }

    private void drain() {
        drainTimer = null;
        drainQueued = false;
        long now = System.nanoTime();
        Delayed head;
        while ((head = delayed.peekFirst()) != null && head.releaseAtNanos() - now <= 0) {
            delayed.pollFirst();
            delayedBytes -= head.data().length();
            writeNow(head.data(), head.onWritten());
        }
        if (head != null) {
            scheduleDrain(head.releaseAtNanos() - now);
        }
        resumeIfDrained();
        finishIfDrained();
    }

    private void writeNow(Buffer data, Runnable onWritten) {
        if (connection.isClosed()) {
            return;
        }
        int size = data.length();
        pendingWrites++;
        sink.write(data).onComplete(ar -> {
            pendingWrites--;
            if (ar.succeeded()) {
                connection.recordBytes(direction, size);
                if (onWritten != null) {
                    onWritten.run();
                }
                finishIfDrained();
            } else {
                connection.closeBoth("write to " + (direction == Direction.UPSTREAM ? "upstream" : "client")
                        + " failed: " + ar.cause());
            }
        });
        if (sink.writeQueueFull()) {
            source.pause();
        }
    }

    /**
     * Stop reading from the source; TCP backpressure builds up on the peer instead.
     */
    public void startHolding() {
        holding = true;
        source.pause();
    }

    /**
     * Resume reading.
     */
    public void release() {
        holding = false;
        resumeIfDrained();
        finishIfDrained();
    }

    /**
     * The source closed normally. Finish only after delayed bytes and outstanding writes have reached the sink.
     * A hanging fault must release its held tail first, so it can still hold a response whose upstream has closed.
     */
    void finish(Runnable action) {
        whenDrained = action;
        finishIfDrained();
    }

    private void finishIfDrained() {
        if (whenDrained != null && !holding && delayed.isEmpty() && pendingWrites == 0 && !connection.isClosed()) {
            Runnable action = whenDrained;
            whenDrained = null;
            action.run();
        }
    }

    public boolean isHolding() {
        return holding;
    }

    void drop() {
        whenDrained = null;
        delayed.clear();
        delayedBytes = 0;
        if (drainTimer != null) {
            drainTimer.cancel();
            drainTimer = null;
        }
    }

    void onSinkWritabilityChanged() {
        if (sink.writeQueueFull()) {
            source.pause();
        } else {
            resumeIfDrained();
        }
    }

    private void pauseIfNeeded() {
        if (delayedBytes > HIGH_WATER) {
            source.pause();
        }
    }

    private void resumeIfDrained() {
        if (!holding && !sink.writeQueueFull() && delayedBytes <= LOW_WATER) {
            source.resume();
        }
    }
}
