package io.quarkiverse.networkfaults.dev;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.runtime.internal.FaultEnd;
import io.quarkiverse.networkfaults.runtime.internal.TargetSnapshot;

class MarkdownReportTest {

    @Test
    void reportListsTargetsAndHistory() {
        String md = MarkdownReport.render(List.of(snapshot("rest-client:inventory", "quarkus.rest-client.inventory.url")),
                List.of(entry("rest-client:inventory", "hangs", "card", true)));
        assertTrue(md.startsWith("# Network faults report"), md);
        assertTrue(md.contains("| rest-client:inventory | quarkus.rest-client.inventory.url |"), md);
        assertTrue(md.contains("hangs"), md);
        assertTrue(md.contains("saw traffic"), md);
    }

    @Test
    void emptyHistorySaysSo() {
        assertTrue(MarkdownReport.render(List.of(), List.of()).contains("No fault has been applied"));
    }

    private static TargetSnapshot snapshot(String name, String sourceProperty) {
        return new TargetSnapshot(name, "localhost", 0, 0, sourceProperty, false, false, "none", null, 0, 0, 0, 0, 0, 0,
                List.of());
    }

    @Test
    void aFaultTheGuardCannotJudgeIsNotJudged() {
        String md = MarkdownReport.render(List.of(snapshot("rest-client:inventory", "quarkus.rest-client.inventory.url")),
                List.of(entry("rest-client:inventory", "unavailable", "card", true, false)));
        assertTrue(md.contains("not judged: the guard could not watch this fault"), md);
        assertFalse(md.contains("saw traffic"), md);
    }

    private static FaultHistory.Entry entry(String target, String description, String source, Boolean sawTraffic) {
        return entry(target, description, source, sawTraffic, true);
    }

    private static FaultHistory.Entry entry(String target, String description, String source, Boolean sawTraffic,
            Boolean observable) {
        FaultEnd.Verdict verdict = sawTraffic == null ? null
                : !observable ? FaultEnd.Verdict.NOT_JUDGED
                        : sawTraffic ? FaultEnd.Verdict.SAW_TRAFFIC : FaultEnd.Verdict.NO_TRAFFIC;
        return new FaultHistory.Entry(0, Instant.EPOCH, null, List.of(target), description,
                "card".equals(source) ? description : null, null, verdict, 0, 0);
    }
}
