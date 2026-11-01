package io.quarkiverse.resiliencytesting.runtime.internal;

import java.time.Instant;
import java.util.List;

/**
 * A fault as it started: when, the targets it was applied to and what it does, as the targets' events describe it.
 * One record per fault handed out, so a listener can match its start and its end by identity.
 */
public record FaultRecord(Instant started, List<String> targets, String description) {
}
