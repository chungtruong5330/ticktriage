package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import dev.ticktriage.core.CensusSchedule;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.WorldCensus;

/**
 * Samples the server on ordinary Paper, where one thread owns everything.
 *
 * <h2>Cost</h2>
 * A plugin that diagnoses lag must not cause any, so the two censuses are
 * treated differently:
 *
 * <ul>
 *   <li><b>Entities</b> - one pass over every loaded chunk per sample. Linear
 *       in entity count and cheap enough at the default 2s interval; the
 *       per-entity accounting is benchmarked in {@code Bench}.</li>
 *   <li><b>Block entities</b> - needs a snapshot of every block entity, which
 *       is far more expensive, so it runs once every {@code blockCensusEvery}
 *       samples and is cached in between. Counts change slowly; TPS does not.</li>
 * </ul>
 */
public final class ServerSampler implements SnapshotSource {

    private final CensusSchedule blockCensus;
    private final Sampling.GlobalStatsReader global =
            new Sampling.GlobalStatsReader();

    private Map<String, Map<String, Integer>> cachedBlockEntities =
            new LinkedHashMap<>();

    public ServerSampler(int blockCensusEvery) {
        this.blockCensus = new CensusSchedule(blockCensusEvery);
    }

    @Override
    public String describe() {
        return "whole-server census every sample";
    }

    /** Must run on the main thread - it touches worlds and chunks. */
    @Override
    public void sample(Consumer<Snapshot> onComplete) {
        Sampling.GlobalStats stats = global.read();

        boolean censusBlocks = blockCensus.due();
        if (censusBlocks) {
            cachedBlockEntities = new LinkedHashMap<>();
        }

        List<Snapshot.WorldStats> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(sampleWorld(world, censusBlocks));
        }

        onComplete.accept(new Snapshot(stats.nowMillis, stats.msPerTick,
                stats.players, stats.heapUsed, stats.heapMax, stats.gcMillis,
                stats.intervalMillis, worlds));
    }

    private Snapshot.WorldStats sampleWorld(World world, boolean censusBlocks) {
        WorldCensus census = new WorldCensus(world.getName());
        Map<String, Integer> blockEntities = censusBlocks
                ? new LinkedHashMap<String, Integer>() : null;

        Chunk[] loaded = world.getLoadedChunks();
        for (Chunk chunk : loaded) {
            Sampling.scanEntities(chunk, census);
            if (blockEntities != null) {
                Sampling.scanBlockEntities(chunk, blockEntities);
            }
        }

        if (blockEntities != null) {
            cachedBlockEntities.put(world.getName(), blockEntities);
        } else {
            blockEntities = cachedBlockEntities.getOrDefault(world.getName(),
                    new LinkedHashMap<String, Integer>());
        }

        return census.finish(loaded.length, blockEntities);
    }
}
