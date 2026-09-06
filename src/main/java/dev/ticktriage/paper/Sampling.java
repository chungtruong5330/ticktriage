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
package dev.ticktriage.paper;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;

import dev.ticktriage.core.WorldCensus;

/**
 * Sampling pieces shared by the Paper and Folia samplers.
 *
 * <p>The two differ only in <em>which thread</em> visits each chunk. What
 * happens once you are there is identical, and lives here so the two paths
 * cannot quietly drift apart and start reporting different numbers.
 */
final class Sampling {

    private Sampling() {
    }

    /** Counts every entity in one chunk. Caller must own that chunk. */
    static void scanEntities(Chunk chunk, WorldCensus census) {
        for (Entity entity : chunk.getEntities()) {
            Location loc = entity.getLocation();
            census.addEntity(entity.getType().name(), loc.getBlockX(),
                    loc.getBlockY(), loc.getBlockZ());
        }
    }

    private static final Logger LOG = Logger.getLogger("TickTriage");
    private static volatile boolean blockCensusFailureLogged = false;

    /**
     * Counts ticking block entities in one chunk. The expensive half.
     *
     * <p>Uses the snapshot-free overload: the default {@code getTileEntities()}
     * builds a full {@link BlockState} copy of every block entity, and all this
     * needs is the material name.
     *
     * <p>A failure here is logged once rather than swallowed. It used to return
     * silently, which meant a census that never worked was indistinguishable
     * from a server with no hoppers - the plugin would have reported "nothing
     * changed" forever and nobody would have known why.
     */
    static void scanBlockEntities(Chunk chunk, Map<String, Integer> counts) {
        BlockState[] states;
        try {
            states = chunk.getTileEntities(false);
        } catch (Throwable t) {
            // A chunk can unload mid-iteration, which is benign and expected.
            // Anything else is a real fault worth surfacing exactly once.
            if (!blockCensusFailureLogged) {
                blockCensusFailureLogged = true;
                LOG.log(Level.WARNING, "Block-entity census failed; hopper and"
                        + " spawner diagnosis will not work until this is"
                        + " resolved.", t);
            }
            return;
        }
        for (BlockState state : states) {
            counts.merge(state.getType().name(), 1, Integer::sum);
        }
    }

    /**
     * Whole-server numbers that are safe to read from any thread, and the
     * bookkeeping needed to turn cumulative GC time into a per-interval figure.
     */
    static final class GlobalStatsReader {

        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private long lastGcTotalMillis = -1;
        private long lastSampleAtMillis = -1;

        GlobalStats read() {
            long now = System.currentTimeMillis();
            long interval = lastSampleAtMillis < 0 ? 0 : now - lastSampleAtMillis;
            lastSampleAtMillis = now;

            long gcTotal = 0;
            for (GarbageCollectorMXBean bean
                    : ManagementFactory.getGarbageCollectorMXBeans()) {
                long t = bean.getCollectionTime();
                if (t > 0) {
                    gcTotal += t;
                }
            }
            long gcDelta = lastGcTotalMillis < 0 ? 0 : gcTotal - lastGcTotalMillis;
            lastGcTotalMillis = gcTotal;

            return new GlobalStats(now, interval, gcDelta,
                    Bukkit.getAverageTickTime(),
                    Bukkit.getOnlinePlayers().size(),
                    memory.getHeapMemoryUsage().getUsed(),
                    memory.getHeapMemoryUsage().getMax());
        }
    }

    static final class GlobalStats {
        final long nowMillis;
        final long intervalMillis;
        final long gcMillis;
        final double msPerTick;
        final int players;
        final long heapUsed;
        final long heapMax;

        GlobalStats(long nowMillis, long intervalMillis, long gcMillis,
                    double msPerTick, int players, long heapUsed,
                    long heapMax) {
            this.nowMillis = nowMillis;
            this.intervalMillis = intervalMillis;
            this.gcMillis = gcMillis;
            this.msPerTick = msPerTick;
            this.players = players;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
        }
    }
}
