/*
 * TickTriage - finds out why a Minecraft server lagged, then fixes it surgically.
 * Copyright (C) 2026 chungtruong5330
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public
 * License for details: <https://www.gnu.org/licenses/>.
 */
package dev.ticktriage.core.rules;

import dev.ticktriage.core.Baseline;
import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.DiagnosisRule;
import dev.ticktriage.core.Incident;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * The server got busy because more people turned up.
 *
 * <p>This rule exists to say "nothing is broken". A tool that always finds a
 * culprit trains owners to ignore it, and sending someone hunting for a
 * phantom farm when they simply hit capacity is worse than staying quiet.
 * It reports INFO and never outranks a real fault.
 */
public final class PlayerSurgeRule implements DiagnosisRule {

    private static final double MIN_DELTA = 8.0;
    private static final double MIN_RATIO = 1.3;

    @Override
    public String id() {
        return "player-surge";
    }

    @Override
    public Diagnosis evaluate(Incident incident, Baseline baseline) {
        if (!baseline.isUsable()) {
            return null;
        }

        Snapshot peak = incident.peak;
        double delta = peak.playerCount - baseline.playerCount;
        double ratio = Stats.ratio(peak.playerCount, baseline.playerCount);

        if (delta < MIN_DELTA || ratio < MIN_RATIO) {
            return null;
        }

        double confidence = 0.30 + 0.45 * (
                0.5 * Stats.scale(ratio, MIN_RATIO, 3.0)
                        + 0.5 * Stats.scale(delta, MIN_DELTA, 60.0));

        return new Diagnosis(id(), confidence, Diagnosis.Severity.INFO,
                peak.playerCount + " players online, up from a normal "
                        + Stats.formatDouble(baseline.playerCount, 0),
                "This looks like ordinary load rather than a fault. If TPS"
                        + " drops predictably at peak hours, the answer is"
                        + " tuning view-distance and simulation-distance, or"
                        + " more CPU - not hunting for a broken build.",
                "Player count " + Stats.formatDouble(ratio, 1)
                        + "x the usual level",
                incident.describeImpact(),
                "Entity and chunk counts scaled with players, not beyond them");
    }
}
