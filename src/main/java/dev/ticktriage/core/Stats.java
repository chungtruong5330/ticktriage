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
package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small statistical helpers.
 *
 * <p>Medians rather than means, everywhere. A lag spike is by definition an
 * outlier, and a mean baseline would be dragged toward the very incident it is
 * supposed to be measured against.
 */
public final class Stats {

    private Stats() {
    }

    public static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public static double medianInts(List<Integer> values) {
        List<Double> as = new ArrayList<>(values.size());
        for (int v : values) {
            as.add((double) v);
        }
        return median(as);
    }

    /**
     * How many times larger {@code observed} is than {@code baseline}.
     * A zero baseline returns a large finite number rather than infinity, so
     * confidence maths downstream stays well behaved.
     */
    public static double ratio(double observed, double baseline) {
        if (baseline <= 0.0) {
            return observed <= 0.0 ? 1.0 : 1000.0;
        }
        return observed / baseline;
    }

    /** Clamp to the 0..1 range used for confidence scores. */
    public static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    /**
     * Maps a value in [lo, hi] onto [0, 1], flat outside the range. Used to
     * turn "how far above baseline" into a confidence contribution without
     * every rule inventing its own curve.
     */
    public static double scale(double value, double lo, double hi) {
        if (hi <= lo) {
            return value >= hi ? 1.0 : 0.0;
        }
        return clamp01((value - lo) / (hi - lo));
    }

    /** Thousands separators, because "4207 dropped items" reads worse. */
    public static String formatCount(long n) {
        return String.format("%,d", n);
    }

    public static String formatDouble(double v, int decimals) {
        return String.format("%." + decimals + "f", v);
    }
}
