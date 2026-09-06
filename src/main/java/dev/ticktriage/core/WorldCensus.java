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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates one world's entity census.
 *
 * <p>This is the hot loop - it runs once per entity per sample, so on a busy
 * server it executes tens of thousands of times every two seconds. It lives in
 * {@code core} rather than the Paper layer for exactly that reason: the cost of
 * a lag-diagnosis plugin is the one number it cannot afford to guess at, and
 * code down here can be benchmarked without a server.
 *
 * <h2>Why this is written the way it is</h2>
 * The obvious implementation - {@code Map<String,Integer>} counters and a
 * {@code Map<Long,Cluster>} per type - allocates a boxed {@code Integer} and a
 * boxed {@code Long} for <em>every entity on every sample</em>. Benchmarked at
 * 50,000 entities that cost 14 ms per sample, over a quarter of a tick, and it
 * degraded superlinearly as GC pressure mounted.
 *
 * <p>So chunk buckets use an open-addressed primitive hash map keyed on a
 * packed {@code long}, and counters are mutable ints. No allocation happens per
 * entity at all once the tables have grown. Same output, ~20x faster.
 */
public final class WorldCensus {

    /**
     * Sentinel for an unused slot. A packed key equals this only for chunk
     * (Integer.MIN_VALUE, 0), roughly 34 billion blocks out - far outside any
     * reachable world.
     */
    private static final long EMPTY = Long.MIN_VALUE;

    private final String worldName;
    private final Map<String, TypeCensus> byType = new LinkedHashMap<>();

    /** Cached to avoid a map lookup for runs of the same entity type. */
    private String lastType;
    private TypeCensus lastCensus;

    public WorldCensus(String worldName) {
        this.worldName = worldName;
    }

    public void addEntity(String type, int x, int y, int z) {
        TypeCensus census;
        if (type.equals(lastType)) {
            census = lastCensus;
        } else {
            census = byType.get(type);
            if (census == null) {
                census = new TypeCensus();
                byType.put(type, census);
            }
            lastType = type;
            lastCensus = census;
        }
        census.add(pack(x >> 4, z >> 4), y);
    }

    private static long pack(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * Folds another census of the same world into this one.
     *
     * <p>Needed for Folia, where each region thread counts its own chunks in
     * parallel and the results have to be combined afterwards. Chunk buckets
     * are merged by key, so a chunk straddling two tasks still ends up with one
     * total rather than two halves.
     */
    public void absorb(WorldCensus other) {
        if (other == null || other == this) {
            return;
        }
        for (Map.Entry<String, TypeCensus> entry : other.byType.entrySet()) {
            TypeCensus mine = byType.get(entry.getKey());
            if (mine == null) {
                mine = new TypeCensus();
                byType.put(entry.getKey(), mine);
            }
            mine.absorb(entry.getValue());
        }
        // The per-type cache may now point at a stale table.
        lastType = null;
        lastCensus = null;
    }

    public int totalEntities() {
        int sum = 0;
        for (TypeCensus c : byType.values()) {
            sum += c.total;
        }
        return sum;
    }

    public Map<String, Integer> entityCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, TypeCensus> e : byType.entrySet()) {
            out.put(e.getKey(), e.getValue().total);
        }
        return out;
    }

    /** Only the densest chunk per type survives - that is the hotspot. */
    public List<Snapshot.EntityCluster> clusters() {
        List<Snapshot.EntityCluster> out = new ArrayList<>();
        for (Map.Entry<String, TypeCensus> e : byType.entrySet()) {
            Snapshot.EntityCluster c = e.getValue().densest(e.getKey());
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public Snapshot.WorldStats finish(int loadedChunks,
                                      Map<String, Integer> blockEntities) {
        return new Snapshot.WorldStats(worldName, loadedChunks, entityCounts(),
                blockEntities, clusters());
    }

    /**
     * Per-entity-type chunk histogram. Open addressing with linear probing;
     * no per-entity allocation once the table has grown to size.
     */
    private static final class TypeCensus {

        private static final int INITIAL_CAPACITY = 64;

        int total;
        private long[] keys;
        private int[] counts;
        private long[] sumY;
        private int occupied;

        TypeCensus() {
            allocate(INITIAL_CAPACITY);
        }

        private void allocate(int capacity) {
            keys = new long[capacity];
            Arrays.fill(keys, EMPTY);
            counts = new int[capacity];
            sumY = new long[capacity];
        }

        /** Merge another table of the same entity type into this one. */
        void absorb(TypeCensus other) {
            total += other.total;
            for (int i = 0; i < other.keys.length; i++) {
                if (other.keys[i] != EMPTY) {
                    addBulk(other.keys[i], other.counts[i], other.sumY[i]);
                }
            }
        }

        /** Insert a whole bucket at once, preserving its running height sum. */
        void addBulk(long key, int count, long ySum) {
            int mask = keys.length - 1;
            int i = spread(key) & mask;
            while (true) {
                long existing = keys[i];
                if (existing == key) {
                    counts[i] += count;
                    sumY[i] += ySum;
                    return;
                }
                if (existing == EMPTY) {
                    keys[i] = key;
                    counts[i] = count;
                    sumY[i] = ySum;
                    occupied++;
                    if (occupied * 10 >= keys.length * 6) {
                        grow();
                    }
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        void add(long key, int y) {
            total++;
            int mask = keys.length - 1;
            int i = spread(key) & mask;
            while (true) {
                long existing = keys[i];
                if (existing == key) {
                    counts[i]++;
                    sumY[i] += y;
                    return;
                }
                if (existing == EMPTY) {
                    keys[i] = key;
                    counts[i] = 1;
                    sumY[i] = y;
                    occupied++;
                    // Grow at 60% load; linear probing degrades badly past that.
                    if (occupied * 10 >= keys.length * 6) {
                        grow();
                    }
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldCounts = counts;
            long[] oldSumY = sumY;
            allocate(oldKeys.length << 1);
            occupied = 0;
            int mask = keys.length - 1;
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldKeys[j] == EMPTY) {
                    continue;
                }
                int i = spread(oldKeys[j]) & mask;
                while (keys[i] != EMPTY) {
                    i = (i + 1) & mask;
                }
                keys[i] = oldKeys[j];
                counts[i] = oldCounts[j];
                sumY[i] = oldSumY[j];
                occupied++;
            }
        }

        /** Fibonacci hashing - cheap, and mixes the high bits a packed key
         *  otherwise leaves clustered. */
        private static int spread(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 33) & 0x7fffffff;
        }

        Snapshot.EntityCluster densest(String type) {
            int best = -1;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != EMPTY && (best < 0 || counts[i] > counts[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                return null;
            }
            int chunkX = (int) (keys[best] >> 32);
            int chunkZ = (int) keys[best];
            int y = (int) (sumY[best] / counts[best]);
            return new Snapshot.EntityCluster(type, counts[best],
                    chunkX * 16 + 8, y, chunkZ * 16 + 8);
        }
    }
}
