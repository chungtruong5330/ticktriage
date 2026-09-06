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
package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds lag incidents in a sample history.
 *
 * <p>A sample counts as bad only if it is slow in absolute terms <em>and</em>
 * slow relative to this server's own baseline. Requiring both is what keeps the
 * plugin quiet on a server that always sits at 19.6 TPS and has made its peace
 * with that.
 */
public final class IncidentDetector {

    /** Below 20 TPS in absolute terms. Nothing under this is worth reporting. */
    public static final double DEFAULT_FLOOR_MS = 55.0;
    public static final double DEFAULT_RATIO = 1.5;
    public static final int DEFAULT_MIN_SAMPLES = 3;
    /** Brief recoveries inside a spike should not split it into two incidents. */
    public static final int DEFAULT_GAP_TOLERANCE = 2;

    private final double floorMs;
    private final double ratio;
    private final int minSamples;
    private final int gapTolerance;

    public IncidentDetector() {
        this(DEFAULT_FLOOR_MS, DEFAULT_RATIO, DEFAULT_MIN_SAMPLES,
                DEFAULT_GAP_TOLERANCE);
    }

    public IncidentDetector(double floorMs, double ratio, int minSamples,
                            int gapTolerance) {
        this.floorMs = floorMs;
        this.ratio = ratio;
        this.minSamples = minSamples;
        this.gapTolerance = gapTolerance;
    }

    public boolean isBad(Snapshot s, Baseline baseline) {
        double relative = baseline.msPerTick * ratio;
        return s.msPerTick > floorMs && s.msPerTick > relative;
    }

    public List<Incident> detect(List<Snapshot> samples, Baseline baseline) {
        List<Incident> incidents = new ArrayList<>();
        List<Snapshot> current = new ArrayList<>();
        List<Snapshot> pendingGap = new ArrayList<>();

        for (Snapshot s : samples) {
            if (isBad(s, baseline)) {
                // A tolerated dip belongs to the incident it interrupted.
                current.addAll(pendingGap);
                pendingGap.clear();
                current.add(s);
            } else if (!current.isEmpty()) {
                pendingGap.add(s);
                if (pendingGap.size() > gapTolerance) {
                    flush(incidents, current);
                    current = new ArrayList<>();
                    pendingGap.clear();
                }
            }
        }
        flush(incidents, current);
        return incidents;
    }

    private void flush(List<Incident> out, List<Snapshot> current) {
        if (current.size() >= minSamples) {
            out.add(new Incident(current));
        }
    }

    /** The most recent incident, or null if the server has been healthy. */
    public Incident mostRecent(List<Snapshot> samples, Baseline baseline) {
        List<Incident> all = detect(samples, baseline);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
