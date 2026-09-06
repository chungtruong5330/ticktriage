package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The result of analysing a history window: what happened, and why. */
public final class Report {

    public final boolean healthy;
    public final Incident incident;
    public final Baseline baseline;
    public final List<Diagnosis> diagnoses;

    private Report(boolean healthy, Incident incident, Baseline baseline,
                   List<Diagnosis> diagnoses) {
        this.healthy = healthy;
        this.incident = incident;
        this.baseline = baseline;
        this.diagnoses = Collections.unmodifiableList(
                new ArrayList<>(diagnoses));
    }

    public static Report healthy(Baseline baseline) {
        return new Report(true, null, baseline, new ArrayList<Diagnosis>());
    }

    public static Report of(Incident incident, Baseline baseline,
                            List<Diagnosis> diagnoses) {
        return new Report(false, incident, baseline, diagnoses);
    }

    /**
     * Something is wrong with the server's steady state, with no incident to
     * point at - a server sitting flat at 49 ms has no spike to report, but is
     * one tick away from visible lag and needs telling.
     */
    public static Report standing(Baseline baseline,
                                  List<Diagnosis> diagnoses) {
        return new Report(false, null, baseline, diagnoses);
    }

    /** True when there is a problem but no discrete incident behind it. */
    public boolean isStanding() {
        return !healthy && incident == null;
    }

    /** Highest-ranked diagnosis, or null when the server is healthy. */
    public Diagnosis primary() {
        return diagnoses.isEmpty() ? null : diagnoses.get(0);
    }

    public String render() {
        if (healthy) {
            double tps = baseline.msPerTick <= 50.0 ? 20.0
                    : 1000.0 / baseline.msPerTick;
            return "No lag incidents in the sampled window. Typical tick time "
                    + Stats.formatDouble(baseline.msPerTick, 1) + " ms ("
                    + Stats.formatDouble(tps, 1) + " TPS) over "
                    + baseline.sampleCount + " samples.";
        }

        StringBuilder sb = new StringBuilder();
        if (incident == null) {
            double tps = baseline.msPerTick <= 50.0 ? 20.0
                    : 1000.0 / baseline.msPerTick;
            sb.append("No spike, but the server's normal state is unhealthy:")
                    .append(" tick time ")
                    .append(Stats.formatDouble(baseline.msPerTick, 1))
                    .append(" ms (").append(Stats.formatDouble(tps, 1))
                    .append(" TPS) over ").append(baseline.sampleCount)
                    .append(" samples");
        } else {
            sb.append("Lag incident: ").append(incident.describeImpact())
                    .append(" for ")
                    .append(Stats.formatDouble(incident.durationSeconds(), 0))
                    .append("s (peak tick time ")
                    .append(Stats.formatDouble(incident.peakMsPerTick(), 0))
                    .append(" ms)");
        }
        for (Diagnosis d : diagnoses) {
            sb.append("\n\n").append(d.render());
        }
        return sb.toString();
    }
}
