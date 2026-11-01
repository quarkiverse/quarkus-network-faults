package io.quarkiverse.resiliencytesting.dev;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultEnd;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultListener;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultRecord;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkiverse.resiliencytesting.runtime.internal.proxy.ProxyRegistry;
import io.quarkus.runtime.Startup;

/**
 * Every fault applied in this dev-mode application, from the Dev UI card, a Dev MCP tool or application code, with
 * how it ended. The most recent ones only are kept. Created at startup, so faults applied from application code
 * before the Dev UI first asks are recorded too.
 */
@ApplicationScoped
@Startup
public class FaultHistory implements FaultListener {

    // the same as the targets' event log, so the history and the events it explains roll at the same pace
    static final int HISTORY_CAPACITY = ProxyRegistry.DEFAULT_EVENT_CAPACITY;

    /**
     * One fault as the history shows it. {@code ended} and {@code verdict} are null while the fault is active;
     * {@code spec} and {@code requests} are null unless it came from the card.
     */
    public record Entry(long id, Instant started, Instant ended, List<String> targets, String description, String spec,
            String requests, FaultEnd.Verdict verdict, long connectionsSeen, long httpRequestsSeen) {
    }

    /**
     * An entry while it is being filled in: tagged after it started, ended later.
     */
    private static final class Slot {
        private final long id;
        private final FaultRecord record;
        private FaultRequest request;
        private FaultEnd end;

        private Slot(long id, FaultRecord record) {
            this.id = id;
            this.record = record;
        }

        private Entry entry() {
            return new Entry(id, record.started(), end == null ? null : end.at(), record.targets(),
                    record.description(), request == null ? null : request.spec(),
                    request == null ? null : request.requests(), end == null ? null : end.verdict(),
                    end == null ? 0 : end.connectionsSeen(), end == null ? 0 : end.httpRequestsSeen());
        }
    }

    // newest first
    private final Deque<Slot> slots = new ArrayDeque<>();
    private long nextId = 1;
    // the entries as last built, dropped on every change: the card asks every two seconds, faults change rarely
    private List<Entry> entries;

    public FaultHistory(Faults faults) {
        ((FaultsImpl) faults).addFaultListener(this);
    }

    @Override
    public synchronized void started(FaultRecord record) {
        slots.addFirst(new Slot(nextId++, record));
        if (slots.size() > HISTORY_CAPACITY) {
            slots.removeLast();
        }
        entries = null;
    }

    @Override
    public synchronized void ended(FaultRecord record, FaultEnd end) {
        Slot slot = find(record);
        if (slot != null) {
            slot.end = end;
            entries = null;
        }
    }

    /**
     * Marks the entry of {@code record} as applied from the card with {@code request}.
     */
    synchronized void tag(FaultRecord record, FaultRequest request) {
        Slot slot = find(record);
        if (slot != null) {
            slot.request = request;
            entries = null;
        }
    }

    private Slot find(FaultRecord record) {
        for (Slot slot : slots) {
            if (slot.record == record) {
                return slot;
            }
        }
        return null;
    }

    /**
     * The entries, newest first.
     */
    synchronized List<Entry> entries() {
        if (entries == null) {
            List<Entry> out = new ArrayList<>(slots.size());
            for (Slot slot : slots) {
                out.add(slot.entry());
            }
            entries = List.copyOf(out);
        }
        return entries;
    }

    /**
     * The last fault that ended on {@code target}, or null.
     */
    Entry lastEnded(String target) {
        for (Entry e : entries()) {
            if (e.ended() != null && e.targets().contains(target)) {
                return e;
            }
        }
        return null;
    }
}
