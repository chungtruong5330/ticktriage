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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What "normal" looks like for this particular server.
 *
 * <p>There is no universal healthy entity count - a big Skyblock server and a
 * ten-player SMP have nothing in common. Every threshold in the rules is
 * relative to what this server usually does, which is what stops the plugin
 * from screaming at servers that are simply large.
 */
public final class Baseline {

    public final int sampleCount;
    public final double msPerTick;
    public final double playerCount;
    public final double loadedChunks;
    public final double heapFraction;

    private final Map<String, Double> worldChunks;
    private final Map<String, Map<String, Double>> worldEntities;
    private final Map<String, Map<String, Double>> worldBlockEntities;

    private Baseline(int sampleCount, double msPerTick, double playerCount,
                     double loadedChunks, double heapFraction,
                     Map<String, Double> worldChunks,
                     Map<String, Map<String, Double>> worldEntities,
                     Map<String, Map<String, Double>> worldBlockEntities) {
        this.sampleCount = sampleCount;
        this.msPerTick = msPerTick;
        this.playerCount = playerCount;
        this.loadedChunks = loadedChunks;
        this.heapFraction = heapFraction;
        this.worldChunks = worldChunks;
        this.worldEntities = worldEntities;
        this.worldBlockEntities = worldBlockEntities;
    }

    /** True once there is enough history for the numbers to mean anything. */
    public boolean isUsable() {
        return sampleCount >= 30;
    }

    public double entities(String world, String type) {
        Map<String, Double> m = worldEntities.get(world);
        if (m == null) {
            return 0.0;
        }
        Double v = m.get(type);
        return v == null ? 0.0 : v;
    }

    public double blockEntities(String world, String type) {
        Map<String, Double> m = worldBlockEntities.get(world);
        if (m == null) {
            return 0.0;
        }
        Double v = m.get(type);
        return v == null ? 0.0 : v;
    }

    public double loadedChunks(String world) {
        Double v = worldChunks.get(world);
        return v == null ? 0.0 : v;
    }

    public static Baseline from(List<Snapshot> samples) {
        if (samples.isEmpty()) {
            return new Baseline(0, 0, 0, 0, 0, new HashMap<>(),
                    new HashMap<>(), new HashMap<>());
        }

        List<Double> ticks = new ArrayList<>();
        List<Integer> players = new ArrayList<>();
        List<Integer> chunks = new ArrayList<>();
        List<Double> heap = new ArrayList<>();
        for (Snapshot s : samples) {
            ticks.add(s.msPerTick);
            players.add(s.playerCount);
            chunks.add(s.totalLoadedChunks());
            heap.add(s.heapUsedFraction());
        }

        // Gather every world and type seen anywhere in the window, so a type
        // that is absent from most samples still gets a real median of zero
        // rather than being silently skipped.
        Set<String> worlds = new HashSet<>();
        Map<String, Set<String>> entityTypes = new HashMap<>();
        Map<String, Set<String>> blockTypes = new HashMap<>();
        for (Snapshot s : samples) {
            for (Snapshot.WorldStats w : s.worlds) {
                worlds.add(w.name);
                entityTypes.computeIfAbsent(w.name, k -> new HashSet<>())
                        .addAll(w.entityCountsByType.keySet());
                blockTypes.computeIfAbsent(w.name, k -> new HashSet<>())
                        .addAll(w.blockEntityCountsByType.keySet());
            }
        }

        Map<String, Double> worldChunks = new LinkedHashMap<>();
        Map<String, Map<String, Double>> worldEntities = new LinkedHashMap<>();
        Map<String, Map<String, Double>> worldBlockEntities = new LinkedHashMap<>();

        for (String world : worlds) {
            List<Integer> wc = new ArrayList<>();
            for (Snapshot s : samples) {
                Snapshot.WorldStats w = s.world(world);
                if (w != null) {
                    wc.add(w.loadedChunks);
                }
            }
            worldChunks.put(world, Stats.medianInts(wc));

            Map<String, Double> ents = new LinkedHashMap<>();
            for (String type : entityTypes.getOrDefault(world, new HashSet<>())) {
                List<Integer> counts = new ArrayList<>();
                for (Snapshot s : samples) {
                    Snapshot.WorldStats w = s.world(world);
                    counts.add(w == null ? 0 : w.entities(type));
                }
                ents.put(type, Stats.medianInts(counts));
            }
            worldEntities.put(world, ents);

            Map<String, Double> blocks = new LinkedHashMap<>();
            for (String type : blockTypes.getOrDefault(world, new HashSet<>())) {
                List<Integer> counts = new ArrayList<>();
                for (Snapshot s : samples) {
                    Snapshot.WorldStats w = s.world(world);
                    counts.add(w == null ? 0 : w.blockEntities(type));
                }
                blocks.put(type, Stats.medianInts(counts));
            }
            worldBlockEntities.put(world, blocks);
        }

        return new Baseline(samples.size(), Stats.median(ticks),
                Stats.medianInts(players), Stats.medianInts(chunks),
                Stats.median(heap), worldChunks, worldEntities,
                worldBlockEntities);
    }
}
