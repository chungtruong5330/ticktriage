package dev.ticktriage.core.rules;

import dev.ticktriage.core.Baseline;
import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.DiagnosisRule;
import dev.ticktriage.core.Incident;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * A spike in loaded chunks. Usually terrain generation - a player running into
 * unexplored land, or a chunk loader keeping regions awake that nobody is in.
 */
public final class ChunkLoadRule implements DiagnosisRule {

    private static final double MIN_DELTA = 400.0;
    private static final double MIN_RATIO = 1.4;
    private static final double SEVERE_DELTA = 3000.0;

    @Override
    public String id() {
        return "chunk-load";
    }

    @Override
    public Diagnosis evaluate(Incident incident, Baseline baseline) {
        if (!baseline.isUsable()) {
            return null;
        }

        Snapshot peak = incident.peak;
        String worstWorld = null;
        double worstDelta = 0.0;
        double worstRatio = 0.0;
        int worstCount = 0;
        double worstBase = 0.0;

        for (Snapshot.WorldStats world : peak.worlds) {
            double base = baseline.loadedChunks(world.name);
            double delta = world.loadedChunks - base;
            double ratio = Stats.ratio(world.loadedChunks, base);
            if (delta < MIN_DELTA || ratio < MIN_RATIO) {
                continue;
            }
            if (delta > worstDelta) {
                worstDelta = delta;
                worstRatio = ratio;
                worstWorld = world.name;
                worstCount = world.loadedChunks;
                worstBase = base;
            }
        }

        if (worstWorld == null) {
            return null;
        }

        double confidence = 0.35 + 0.65 * (
                0.5 * Stats.scale(worstRatio, MIN_RATIO, 4.0)
                        + 0.5 * Stats.scale(worstDelta, MIN_DELTA, SEVERE_DELTA));

        Diagnosis.Severity severity = worstDelta >= SEVERE_DELTA / 2.0
                ? Diagnosis.Severity.CRITICAL : Diagnosis.Severity.WARNING;

        return new Diagnosis(id(), confidence, severity,
                Stats.formatCount(worstCount) + " chunks loaded in world '"
                        + worstWorld + "'",
                "If players are exploring, pre-generate the world with Chunky"
                        + " and set a world border - generating terrain live is"
                        + " far more expensive than loading it. If the count"
                        + " stays high with nobody there, something is holding"
                        + " chunks open: check for chunk loaders, portal-linked"
                        + " farms, or a plugin with a stuck async task.",
                Stats.formatCount(worstCount) + " chunks versus a normal "
                        + Stats.formatCount(Math.round(worstBase))
                        + " (" + Stats.formatDouble(worstRatio, 1) + "x)",
                incident.describeImpact() + " for "
                        + Stats.formatDouble(incident.durationSeconds(), 0) + "s",
                peak.playerCount + " players online at the time");
    }
}
