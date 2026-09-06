package dev.ticktriage.core.rules;

import java.util.Map;

import dev.ticktriage.core.Baseline;
import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.DiagnosisRule;
import dev.ticktriage.core.DiagnosisTarget;
import dev.ticktriage.core.Incident;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * Shared logic for "there is far more of something than usual".
 *
 * <p>Entities and block entities are counted separately and fixed differently,
 * but the detection is identical: find the type whose count at peak most
 * exceeds its own baseline, by both a ratio and an absolute margin.
 *
 * <p>Both margins are required on purpose. A ratio alone flags a world going
 * from 2 armour stands to 6; an absolute threshold alone flags a big server for
 * being big. Only the pair identifies something that actually changed.
 */
abstract class FloodRule implements DiagnosisRule {

    protected abstract Map<String, Integer> counts(Snapshot.WorldStats world);

    protected abstract double baselineFor(Baseline baseline, String world,
                                          String type);

    /** Plural noun used in the headline, e.g. "dropped items". */
    protected abstract String describe(String type, int count);

    protected abstract String adviceFor(String type, String world,
                                        Snapshot.EntityCluster cluster);

    protected abstract double minAbsolute();

    protected abstract double minRatio();

    /** Saturation point for scoring - beyond this it is simply very bad. */
    protected abstract double severeAbsolute();

    /** Block entities are diagnosed but never auto-removed. */
    protected abstract boolean isBlockEntity();

    @Override
    public Diagnosis evaluate(Incident incident, Baseline baseline) {
        if (!baseline.isUsable()) {
            return null;
        }

        Snapshot peak = incident.peak;
        String worstWorld = null;
        String worstType = null;
        double worstDelta = 0.0;
        double worstRatio = 0.0;
        int worstCount = 0;
        double worstBase = 0.0;

        for (Snapshot.WorldStats world : peak.worlds) {
            for (Map.Entry<String, Integer> entry : counts(world).entrySet()) {
                String type = entry.getKey();
                int observed = entry.getValue();
                double base = baselineFor(baseline, world.name, type);
                double delta = observed - base;
                double ratio = Stats.ratio(observed, base);

                if (delta < minAbsolute() || ratio < minRatio()) {
                    continue;
                }
                if (delta > worstDelta) {
                    worstDelta = delta;
                    worstRatio = ratio;
                    worstWorld = world.name;
                    worstType = type;
                    worstCount = observed;
                    worstBase = base;
                }
            }
        }

        if (worstType == null) {
            return null;
        }

        double ratioScore = Stats.scale(worstRatio, minRatio(), 8.0);
        double absScore = Stats.scale(worstDelta, minAbsolute(), severeAbsolute());
        double confidence = 0.35 + 0.65 * (0.5 * ratioScore + 0.5 * absScore);

        Snapshot.WorldStats world = peak.world(worstWorld);
        Snapshot.EntityCluster cluster = world == null ? null
                : world.densestCluster(worstType);

        Diagnosis.Severity severity =
                (worstDelta >= severeAbsolute() / 2.0 || incident.worstTps() < 15.0)
                        ? Diagnosis.Severity.CRITICAL
                        : Diagnosis.Severity.WARNING;

        String where = cluster == null ? "in world '" + worstWorld + "'"
                : "in world '" + worstWorld + "' around " + cluster.coords();

        String headline = describe(worstType, worstCount) + " " + where;

        String[] evidence = new String[] {
                Stats.formatCount(worstCount) + " at peak versus a normal "
                        + Stats.formatCount(Math.round(worstBase))
                        + " (" + Stats.formatDouble(worstRatio, 1) + "x)",
                "TPS fell to " + Stats.formatDouble(incident.worstTps(), 1)
                        + " for " + Stats.formatDouble(incident.durationSeconds(), 0)
                        + "s",
                cluster == null ? "No single hotspot - spread across the world"
                        : Stats.formatCount(cluster.count)
                        + " of them clustered at " + cluster.coords()
        };

        // Coordinates come back as the chunk centre, so shifting recovers the
        // chunk the hotspot sits in.
        DiagnosisTarget target = new DiagnosisTarget(worstWorld, worstType,
                isBlockEntity(), cluster != null,
                cluster == null ? 0 : cluster.x >> 4,
                cluster == null ? 0 : cluster.z >> 4,
                worstCount, (int) Math.round(worstBase));

        return new Diagnosis(id(), confidence, severity, headline,
                adviceFor(worstType, worstWorld, cluster), evidence)
                .withTarget(target);
    }
}
