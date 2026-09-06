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
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * The heap is nearly full and the JVM is spending its time collecting garbage
 * rather than ticking.
 *
 * <p>Both conditions are required. A heap that sits at 95% is normal for a JVM
 * that has been given a large heap and has no reason to collect - high usage
 * alone is the single most misread number in Minecraft server administration,
 * and owners buy RAM they do not need because of it. Only high usage
 * <em>plus</em> real GC pause time indicates a genuine problem.
 */
public final class MemoryPressureRule implements DiagnosisRule {

    private static final double MIN_HEAP_FRACTION = 0.90;
    private static final double MIN_GC_FRACTION = 0.10;
    private static final double SEVERE_GC_FRACTION = 0.35;

    @Override
    public String id() {
        return "memory-pressure";
    }

    @Override
    public Diagnosis evaluate(Incident incident, Baseline baseline) {
        Snapshot peak = incident.peak;
        double heap = peak.heapUsedFraction();
        double gc = peak.gcFraction();

        if (heap < MIN_HEAP_FRACTION || gc < MIN_GC_FRACTION) {
            return null;
        }

        double confidence = 0.4 + 0.6 * (
                0.4 * Stats.scale(heap, MIN_HEAP_FRACTION, 0.99)
                        + 0.6 * Stats.scale(gc, MIN_GC_FRACTION, SEVERE_GC_FRACTION));

        Diagnosis.Severity severity = gc >= SEVERE_GC_FRACTION
                ? Diagnosis.Severity.CRITICAL : Diagnosis.Severity.WARNING;

        long usedMb = peak.heapUsedBytes / (1024 * 1024);
        long maxMb = peak.heapMaxBytes / (1024 * 1024);

        boolean heapGrewWithoutPlayers =
                baseline.heapFraction > 0.85 && peak.playerCount <= baseline.playerCount;

        String fix = heapGrewWithoutPlayers
                ? "Memory is high even at normal player counts, which points to"
                + " a leak rather than a shortage. Restarting will mask it."
                + " Take a heap dump and check which plugin is retaining"
                + " objects before buying more RAM."
                : "Give the JVM more heap if the host has it spare, and make"
                + " sure you are running Aikar's GC flags - the default"
                + " collector pauses far longer than G1 tuned for Minecraft."
                + " If the heap is already generous, look for a plugin caching"
                + " without bounds.";

        return new Diagnosis(id(), confidence, severity,
                "Garbage collection is stalling ticks ("
                        + Stats.formatDouble(gc * 100, 0) + "% of the interval)",
                fix,
                "Heap at " + Stats.formatCount(usedMb) + " MB of "
                        + Stats.formatCount(maxMb) + " MB ("
                        + Stats.formatDouble(heap * 100, 0) + "%)",
                Stats.formatCount(peak.gcMillisInInterval) + " ms of GC pauses in a "
                        + Stats.formatCount(peak.intervalMillis) + " ms window",
                incident.describeImpact() + " with " + peak.playerCount
                        + " players online");
    }
}
