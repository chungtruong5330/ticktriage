/*
 * TickTriage - finds out why a Minecraft server lagged, then fixes it surgically.
 * Copyright (C) 2026 slateline
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
import dev.ticktriage.core.Stats;

/**
 * The server is not spiking - it is permanently over budget.
 *
 * <p>Worth stating separately because the fix is completely different. Owners
 * in this state usually chase individual lag spikes for weeks without noticing
 * that their <em>baseline</em> never reaches 20 TPS in the first place.
 *
 * <p>This is the one rule that reports without an incident, and it has to be.
 * A server sitting flat at 49 ms produces no spike for a detector to catch -
 * its median <em>is</em> the problem - so requiring an incident first made this
 * rule unreachable on exactly the servers it was written for. That was a real
 * bug, found by running the plugin on a server loaded to 49 ms, where it
 * cheerfully reported "no lag incidents".
 */
public final class ChronicOverloadRule implements DiagnosisRule {

    /** A tick has a 50 ms budget; consistently above 45 ms leaves no headroom. */
    private static final double WARN_MS = 45.0;
    private static final double SEVERE_MS = 60.0;

    @Override
    public String id() {
        return "chronic-overload";
    }

    @Override
    public Diagnosis evaluate(Incident incident, Baseline baseline) {
        return evaluateBaseline(baseline);
    }

    @Override
    public Diagnosis evaluateBaseline(Baseline baseline) {
        if (baseline == null || !baseline.isUsable()
                || baseline.msPerTick < WARN_MS) {
            return null;
        }

        double confidence = 0.5 + 0.5 * Stats.scale(baseline.msPerTick,
                WARN_MS, SEVERE_MS);
        double baselineTps = baseline.msPerTick <= 50.0 ? 20.0
                : 1000.0 / baseline.msPerTick;

        Diagnosis.Severity severity = baseline.msPerTick >= SEVERE_MS
                ? Diagnosis.Severity.CRITICAL : Diagnosis.Severity.WARNING;

        return new Diagnosis(id(), confidence, severity,
                "Baseline performance is already poor - normal tick time is "
                        + Stats.formatDouble(baseline.msPerTick, 1) + " ms",
                "This server has no tick headroom even when nothing is going"
                        + " wrong, so individual spikes are a symptom rather"
                        + " than the disease. Reduce view-distance and"
                        + " simulation-distance first, then profile with spark"
                        + " to find which plugins or entities own the baseline"
                        + " cost.",
                "Median tick time " + Stats.formatDouble(baseline.msPerTick, 1)
                        + " ms against a 50 ms budget",
                "Typical TPS around " + Stats.formatDouble(baselineTps, 1)
                        + " before any incident",
                "Measured over " + baseline.sampleCount + " samples with a"
                        + " median of " + Stats.formatDouble(baseline.playerCount, 0)
                        + " players");
    }
}
