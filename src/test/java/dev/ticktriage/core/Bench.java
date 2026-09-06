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

import java.util.LinkedHashMap;
import java.util.Random;

/**
 * Measures the sampler's own per-entity cost.
 *
 * <p>{@code java dev.ticktriage.core.Bench}
 *
 * <p>This benchmarks {@link WorldCensus} - the real production hot loop, not a
 * stand-in. It does <em>not</em> include the cost of Bukkit's
 * {@code world.getEntities()} call itself, which has to be measured on a live
 * server. What it answers is the question we control: given the entities, how
 * much does our own accounting add?
 */
public final class Bench {

    private static final String[] TYPES = {
            "ITEM", "ZOMBIE", "SKELETON", "CREEPER", "VILLAGER", "COW",
            "SHEEP", "ARMOR_STAND", "EXPERIENCE_ORB", "ARROW", "PIG", "CHICKEN"
    };

    /** Entities cluster heavily in real worlds; uniform noise would flatter us. */
    private static int[][] generate(int count, long seed) {
        Random random = new Random(seed);
        int[][] out = new int[count][4];
        int hotspots = Math.max(1, count / 500);
        int[][] centres = new int[hotspots][2];
        for (int i = 0; i < hotspots; i++) {
            centres[i][0] = random.nextInt(20000) - 10000;
            centres[i][1] = random.nextInt(20000) - 10000;
        }
        for (int i = 0; i < count; i++) {
            int[] centre = centres[random.nextInt(hotspots)];
            out[i][0] = random.nextInt(TYPES.length);
            out[i][1] = centre[0] + random.nextInt(160) - 80;
            out[i][2] = random.nextInt(120);
            out[i][3] = centre[1] + random.nextInt(160) - 80;
        }
        return out;
    }

    private static double runOnce(int[][] entities) {
        long start = System.nanoTime();
        WorldCensus census = new WorldCensus("world");
        for (int[] e : entities) {
            census.addEntity(TYPES[e[0]], e[1], e[2], e[3]);
        }
        census.finish(8000, new LinkedHashMap<String, Integer>());
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void measure(int count) {
        int[][] entities = generate(count, 42L);

        for (int i = 0; i < 200; i++) {
            runOnce(entities);
        }

        int runs = 300;
        double total = 0;
        double worst = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            double ms = runOnce(entities);
            total += ms;
            worst = Math.max(worst, ms);
            best = Math.min(best, ms);
        }

        double mean = total / runs;
        System.out.printf("  %,9d entities   mean %6.2f ms   best %6.2f ms"
                        + "   worst %6.2f ms   %5.3f %% of a 50ms tick%n",
                count, mean, best, worst, (mean / 50.0) * 100.0);
    }

    public static void main(String[] args) {
        System.out.println("\nWorldCensus cost - the per-sample accounting we control");
        System.out.println("(excludes Bukkit's own getEntities() call)");
        System.out.println("-------------------------------------------------"
                + "------------------------------------");
        measure(1_000);
        measure(10_000);
        measure(50_000);
        measure(150_000);
        System.out.println();
        System.out.println("At the default 2-second sample interval, a sample"
                + " costing X ms uses X/2000 of wall clock.");
    }
}
