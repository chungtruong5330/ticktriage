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
package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.ticktriage.core.CensusSchedule;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.WorldCensus;

/**
 * Samples the server on Folia, where no single thread may look at the whole
 * world.
 *
 * <p>Each region ticks on its own thread and only that thread may touch its
 * chunks, so the census is fanned out and merged rather than walked in one
 * pass. Work is aimed with the <b>entity</b> scheduler on each online player:
 * it follows the player if they cross a region boundary before the task fires,
 * which the region scheduler would not.
 *
 * <p><b>Known limitation.</b> This sees the chunks around players, not every
 * loaded chunk. A chunk loader running an unattended farm in an empty corner of
 * the world is invisible here, where on Paper it would be counted. That is a
 * real behavioural difference between the two platforms and is documented
 * rather than papered over - {@code /ticktriage status} says which strategy is
 * in use.
 */
public final class FoliaSampler implements SnapshotSource {

    /** Chunks either side of a player to census. 6 covers a 13x13 area. */
    public static final int DEFAULT_SCAN_RADIUS = 6;

    /**
     * If a round has not finished in this long, something swallowed a region
     * task. Abandon it rather than wedging sampling forever.
     */
    private static final long ROUND_TIMEOUT_MILLIS = 30_000L;

    private final Platform platform;
    private final CensusSchedule blockCensus;
    private final int scanRadius;
    private final Sampling.GlobalStatsReader global =
            new Sampling.GlobalStatsReader();

    private final AtomicReference<Round> inFlight = new AtomicReference<>();
    private volatile Map<String, Map<String, Integer>> cachedBlockEntities =
            new ConcurrentHashMap<>();

    public FoliaSampler(Platform platform, int blockCensusEvery,
                        int scanRadius) {
        this.platform = platform;
        this.blockCensus = new CensusSchedule(blockCensusEvery);
        this.scanRadius = Math.max(1, scanRadius);
    }

    @Override
    public String describe() {
        return "regionised census, " + scanRadius + " chunks around each player";
    }

    /** Called on the global tick thread. */
    @Override
    public void sample(Consumer<Snapshot> onComplete) {
        Round previous = inFlight.get();
        if (previous != null) {
            if (System.currentTimeMillis() - previous.startedAtMillis
                    < ROUND_TIMEOUT_MILLIS) {
                // Still collecting. Skipping a sample is much better than
                // interleaving two rounds into one snapshot.
                return;
            }
            inFlight.compareAndSet(previous, null);
        }

        Sampling.GlobalStats stats = global.read();
        boolean censusBlocks = blockCensus.due();

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            // Nothing worth censusing, but memory and tick time still matter.
            onComplete.accept(assemble(stats,
                    Collections.<String, WorldCensus>emptyMap(),
                    Collections.<String, Integer>emptyMap(), censusBlocks,
                    Collections.<String, Map<String, Integer>>emptyMap()));
            return;
        }

        Round round = new Round(stats, players.size(), censusBlocks, onComplete);
        if (!inFlight.compareAndSet(null, round)) {
            return;
        }

        for (Player player : players) {
            platform.runForEntity(player, () -> scanAround(player, round),
                    () -> round.taskDone());
        }
    }

    /** Runs on the region thread that owns this player. */
    private void scanAround(Player player, Round round) {
        try {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            int centreX = location.getBlockX() >> 4;
            int centreZ = location.getBlockZ() >> 4;

            WorldCensus census = new WorldCensus(world.getName());
            Map<String, Integer> blockEntities = round.censusBlocks
                    ? new LinkedHashMap<String, Integer>() : null;
            int scanned = 0;

            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    int cx = centreX + dx;
                    int cz = centreZ + dz;
                    if (!platform.ownsChunk(world, cx, cz)
                            || !world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    // Two players standing together must not double-count the
                    // chunks they share.
                    if (!round.claimChunk(world.getName(), cx, cz)) {
                        continue;
                    }
                    Chunk chunk = world.getChunkAt(cx, cz);
                    Sampling.scanEntities(chunk, census);
                    if (blockEntities != null) {
                        Sampling.scanBlockEntities(chunk, blockEntities);
                    }
                    scanned++;
                }
            }
            round.submit(world.getName(), census, blockEntities, scanned);
        } catch (Throwable t) {
            // A failed region must not hang the whole round.
        } finally {
            round.taskDone();
        }
    }

    private Snapshot assemble(Sampling.GlobalStats stats,
                              Map<String, WorldCensus> censuses,
                              Map<String, Integer> chunkCounts,
                              boolean censusBlocks,
                              Map<String, Map<String, Integer>> blockEntities) {
        if (censusBlocks) {
            cachedBlockEntities = new ConcurrentHashMap<>(blockEntities);
        }
        Map<String, Map<String, Integer>> blocks = cachedBlockEntities;

        List<Snapshot.WorldStats> worlds = new ArrayList<>();
        for (Map.Entry<String, WorldCensus> entry : censuses.entrySet()) {
            Map<String, Integer> worldBlocks = blocks.get(entry.getKey());
            worlds.add(entry.getValue().finish(
                    chunkCounts.getOrDefault(entry.getKey(), 0),
                    worldBlocks == null
                            ? new LinkedHashMap<String, Integer>() : worldBlocks));
        }
        return new Snapshot(stats.nowMillis, stats.msPerTick, stats.players,
                stats.heapUsed, stats.heapMax, stats.gcMillis,
                stats.intervalMillis, worlds);
    }

    /** One fan-out round, collecting results from every region thread. */
    private final class Round {

        final long startedAtMillis = System.currentTimeMillis();
        final Sampling.GlobalStats stats;
        final boolean censusBlocks;
        final Consumer<Snapshot> onComplete;
        final AtomicInteger outstanding;

        private final Set<String> claimedChunks = ConcurrentHashMap.newKeySet();
        private final Map<String, WorldCensus> censuses = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> blockEntities =
                new LinkedHashMap<>();
        private final Map<String, Integer> chunkCounts = new LinkedHashMap<>();

        Round(Sampling.GlobalStats stats, int tasks, boolean censusBlocks,
              Consumer<Snapshot> onComplete) {
            this.stats = stats;
            this.censusBlocks = censusBlocks;
            this.onComplete = onComplete;
            this.outstanding = new AtomicInteger(tasks);
        }

        boolean claimChunk(String world, int chunkX, int chunkZ) {
            return claimedChunks.add(world + ":" + chunkX + ":" + chunkZ);
        }

        /** Called from region threads, hence the lock. */
        synchronized void submit(String world, WorldCensus census,
                                 Map<String, Integer> blocks, int chunksScanned) {
            WorldCensus existing = censuses.get(world);
            if (existing == null) {
                censuses.put(world, census);
            } else {
                existing.absorb(census);
            }
            chunkCounts.merge(world, chunksScanned, Integer::sum);
            if (blocks != null) {
                Map<String, Integer> target = blockEntities.get(world);
                if (target == null) {
                    blockEntities.put(world, new LinkedHashMap<>(blocks));
                } else {
                    for (Map.Entry<String, Integer> e : blocks.entrySet()) {
                        target.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
        }

        void taskDone() {
            if (outstanding.decrementAndGet() > 0) {
                return;
            }
            inFlight.compareAndSet(this, null);
            // Hop back to the global thread before touching plugin state.
            platform.runGlobal(() -> {
                Snapshot snapshot;
                synchronized (this) {
                    snapshot = assemble(stats, censuses, chunkCounts,
                            censusBlocks, blockEntities);
                }
                onComplete.accept(snapshot);
            });
        }
    }
}
