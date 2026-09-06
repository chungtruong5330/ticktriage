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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable sample of server state.
 *
 * <p>Nothing in this package imports Paper or Bukkit. The platform layer
 * produces these; everything below this line is plain Java, which is what
 * makes the diagnosis engine testable without a running server.
 */
public final class Snapshot {

    public final long timestampMillis;
    public final double msPerTick;
    public final int playerCount;
    public final long heapUsedBytes;
    public final long heapMaxBytes;
    /** GC pause time attributable to the interval since the previous sample. */
    public final long gcMillisInInterval;
    public final long intervalMillis;
    public final List<WorldStats> worlds;

    public Snapshot(long timestampMillis, double msPerTick, int playerCount,
                    long heapUsedBytes, long heapMaxBytes,
                    long gcMillisInInterval, long intervalMillis,
                    List<WorldStats> worlds) {
        this.timestampMillis = timestampMillis;
        this.msPerTick = msPerTick;
        this.playerCount = playerCount;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.gcMillisInInterval = gcMillisInInterval;
        this.intervalMillis = intervalMillis;
        this.worlds = Collections.unmodifiableList(new ArrayList<>(worlds));
    }

    /** Ticks per second implied by tick duration, capped at the vanilla 20. */
    public double tps() {
        if (msPerTick <= 50.0) {
            return 20.0;
        }
        return 1000.0 / msPerTick;
    }

    public double heapUsedFraction() {
        return heapMaxBytes <= 0 ? 0.0 : (double) heapUsedBytes / heapMaxBytes;
    }

    /** Share of wall-clock time spent in GC pauses over this interval. */
    public double gcFraction() {
        return intervalMillis <= 0 ? 0.0
                : Math.min(1.0, (double) gcMillisInInterval / intervalMillis);
    }

    public int totalEntities() {
        int sum = 0;
        for (WorldStats w : worlds) {
            sum += w.totalEntities();
        }
        return sum;
    }

    public int totalLoadedChunks() {
        int sum = 0;
        for (WorldStats w : worlds) {
            sum += w.loadedChunks;
        }
        return sum;
    }

    public WorldStats world(String name) {
        for (WorldStats w : worlds) {
            if (w.name.equals(name)) {
                return w;
            }
        }
        return null;
    }

    /** Per-world counts. Entity and block-entity totals are kept separate
     *  because they fail for completely different reasons and have completely
     *  different fixes. */
    public static final class WorldStats {
        public final String name;
        public final int loadedChunks;
        public final Map<String, Integer> entityCountsByType;
        public final Map<String, Integer> blockEntityCountsByType;
        public final List<EntityCluster> clusters;

        public WorldStats(String name, int loadedChunks,
                          Map<String, Integer> entityCountsByType,
                          Map<String, Integer> blockEntityCountsByType,
                          List<EntityCluster> clusters) {
            this.name = name;
            this.loadedChunks = loadedChunks;
            this.entityCountsByType = Collections.unmodifiableMap(
                    new LinkedHashMap<>(entityCountsByType));
            this.blockEntityCountsByType = Collections.unmodifiableMap(
                    new LinkedHashMap<>(blockEntityCountsByType));
            this.clusters = Collections.unmodifiableList(
                    new ArrayList<>(clusters));
        }

        public int totalEntities() {
            int sum = 0;
            for (int v : entityCountsByType.values()) {
                sum += v;
            }
            return sum;
        }

        public int totalBlockEntities() {
            int sum = 0;
            for (int v : blockEntityCountsByType.values()) {
                sum += v;
            }
            return sum;
        }

        public int entities(String type) {
            Integer v = entityCountsByType.get(type);
            return v == null ? 0 : v;
        }

        public int blockEntities(String type) {
            Integer v = blockEntityCountsByType.get(type);
            return v == null ? 0 : v;
        }

        /** Densest cluster of the given type, or null if none was recorded. */
        public EntityCluster densestCluster(String type) {
            EntityCluster best = null;
            for (EntityCluster c : clusters) {
                if (c.type.equals(type) && (best == null || c.count > best.count)) {
                    best = c;
                }
            }
            return best;
        }
    }

    /**
     * A spatial hotspot. This is the difference between "you have too many
     * items" and "you have too many items *here*", which is the difference
     * between a stat and a fix.
     */
    public static final class EntityCluster {
        public final String type;
        public final int count;
        public final int x;
        public final int y;
        public final int z;

        public EntityCluster(String type, int count, int x, int y, int z) {
            this.type = type;
            this.count = count;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String coords() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }
}
