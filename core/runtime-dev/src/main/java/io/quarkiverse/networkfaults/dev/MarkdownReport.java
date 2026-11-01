package io.quarkiverse.networkfaults.dev;

import java.util.List;

import io.quarkiverse.networkfaults.runtime.internal.TargetSnapshot;

/**
 * A Markdown account of the fault experiments run against this dev-mode application, to paste into an issue or a
 * pull request: the targets with their counters, and the faults applied with whether traffic went through them.
 */
final class MarkdownReport {

    private MarkdownReport() {
    }

    static String render(List<TargetSnapshot> targets, List<FaultHistory.Entry> history) {
        StringBuilder md = new StringBuilder("# Network faults report\n\n");
        md.append("## Targets\n\n");
        if (targets.isEmpty()) {
            md.append("No target has been proxied yet.\n\n");
        } else {
            md.append("| Target | Property | Upstream | Active fault | Connections seen | Requests seen "
                    + "| Connections reset | Upstream failures |\n");
            md.append("|---|---|---|---|---|---|---|---|\n");
            for (TargetSnapshot t : targets) {
                md.append("| ").append(t.name())
                        .append(" | ").append(t.sourceProperty())
                        .append(" | ").append(t.upstream())
                        .append(" | ").append(t.fault())
                        .append(" | ").append(t.connectionsSeen())
                        .append(" | ").append(t.httpRequestsSeen())
                        .append(" | ").append(t.connectionsReset())
                        .append(" | ").append(t.upstreamConnectFailures())
                        .append(" |\n");
            }
            md.append('\n');
        }
        md.append("## Faults applied\n\n");
        if (history.isEmpty()) {
            md.append("No fault has been applied since the application started.\n\n");
        }
        for (FaultHistory.Entry e : history) {
            md.append("### ").append(e.description()).append(" on ").append(String.join(", ", e.targets()))
                    .append("\n\n");
            md.append("- Started: ").append(e.started()).append('\n');
            md.append("- Ended: ").append(e.ended() == null ? "still active" : e.ended().toString()).append('\n');
            md.append("- Source: ");
            if (e.spec() == null) {
                md.append("application code");
            } else {
                md.append("card (`").append(e.spec()).append('`');
                if (e.requests() != null) {
                    md.append(" on `").append(e.requests()).append('`');
                }
                md.append(')');
            }
            md.append('\n');
            md.append("- Traffic: ").append(traffic(e)).append("\n\n");
        }
        return md.toString();
    }

    private static String traffic(FaultHistory.Entry e) {
        if (e.verdict() == null) {
            return "not judged until the fault ends";
        }
        return switch (e.verdict()) {
            case NOT_JUDGED -> "not judged: the guard could not watch this fault";
            case SAW_TRAFFIC -> "saw traffic, " + e.connectionsSeen() + " connections and " + e.httpRequestsSeen()
                    + " HTTP requests while it was active";
            case NO_TRAFFIC -> "no connection went through the proxy while it was active";
        };
    }
}
