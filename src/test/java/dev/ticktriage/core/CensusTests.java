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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Differential tests for {@link WorldCensus}.
 *
 * <p>{@code java dev.ticktriage.core.CensusTests}
 *
 * <p>WorldCensus contains a hand-written open-addressed hash map, written for
 * speed. Hand-written hash maps are exactly the kind of code that is quietly
 * wrong on the paths nobody tries, so every test here checks it against an
 * obviously-correct {@code HashMap} reference over randomised input rather than
 * against hand-picked expectations.
 */
public final class CensusTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    /** The obvious, slow, obviously-correct version. */
    private static final class Reference {
        final Map<String, Integer> totals = new LinkedHashMap<>();
        final Map<String, Map<Long, int[]>> chunks = new HashMap<>();

        void add(String type, int x, int y, int z) {
            totals.merge(type, 1, Integer::sum);
            long key = (((long) (x >> 4)) << 32) | ((z >> 4) & 0xffffffffL);
            int[] cell = chunks.computeIfAbsent(type, k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new int[]{0, 0});
            cell[0]++;
            cell[1] += y;
        }

        /** type -> {count, chunkX, chunkZ, avgY} for the densest chunk. */
        Map<String, int[]> densest() {
            Map<String, int[]> out = new HashMap<>();
            for (Map.Entry<String, Map<Long, int[]>> e : chunks.entrySet()) {
                long bestKey = 0;
                int[] best = null;
                for (Map.Entry<Long, int[]> c : e.getValue().entrySet()) {
                    if (best == null || c.getValue()[0] > best[0]) {
                        best = c.getValue();
                        bestKey = c.getKey();
                    }
                }
                if (best != null) {
                    out.put(e.getKey(), new int[]{best[0],
                            (int) (bestKey >> 32), (int) bestKey,
                            best[1] / best[0]});
                }
            }
            return out;
        }
    }

    private static Snapshot.EntityCluster clusterFor(
            List<Snapshot.EntityCluster> clusters, String type) {
        for (Snapshot.EntityCluster c : clusters) {
            if (c.type.equals(type)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Runs identical input through both implementations and compares totals and
     * hotspots. Any disagreement is a bug in the fast path.
     */
    private static void differential(String name, int entities, int spread,
                                     long seed) {
        String[] types = {"ITEM", "ZOMBIE", "ARROW", "COW", "ARMOR_STAND"};
        Random random = new Random(seed);
        WorldCensus fast = new WorldCensus("world");
        Reference slow = new Reference();

        for (int i = 0; i < entities; i++) {
            String type = types[random.nextInt(types.length)];
            int x = random.nextInt(spread) - spread / 2;
            int y = random.nextInt(320) - 64;
            int z = random.nextInt(spread) - spread / 2;
            fast.addEntity(type, x, y, z);
            slow.add(type, x, y, z);
        }

        boolean totalsMatch = fast.entityCounts().equals(slow.totals);
        check(name + ": per-type totals match the reference", totalsMatch,
                fast.entityCounts() + " vs " + slow.totals);

        List<Snapshot.EntityCluster> clusters = fast.clusters();
        Map<String, int[]> expected = slow.densest();
        boolean clustersMatch = clusters.size() == expected.size();
        String detail = "";
        for (Map.Entry<String, int[]> e : expected.entrySet()) {
            Snapshot.EntityCluster got = clusterFor(clusters, e.getKey());
            if (got == null) {
                clustersMatch = false;
                detail = "missing cluster for " + e.getKey();
                break;
            }
            // Ties between equally dense chunks may resolve differently; the
            // count is what must agree.
            if (got.count != e.getValue()[0]) {
                clustersMatch = false;
                detail = e.getKey() + " count " + got.count + " != "
                        + e.getValue()[0];
                break;
            }
        }
        check(name + ": densest-chunk counts match the reference", clustersMatch,
                detail);
    }

    static void testSmallScatter() {
        differential("small scatter", 500, 512, 1L);
    }

    static void testDenseHotspot() {
        // Everything inside a few chunks - maximum collisions on one key.
        differential("dense hotspot", 20_000, 48, 2L);
    }

    static void testWideSpreadForcesManyResizes() {
        differential("wide spread", 60_000, 200_000, 3L);
    }

    static void testNegativeCoordinates() {
        WorldCensus fast = new WorldCensus("world");
        Reference slow = new Reference();
        for (int x = -600; x < 600; x += 7) {
            for (int z = -600; z < 600; z += 11) {
                fast.addEntity("ITEM", x, 64, z);
                slow.add("ITEM", x, 64, z);
            }
        }
        check("negative coordinates are bucketed identically",
                fast.entityCounts().equals(slow.totals)
                        && clusterFor(fast.clusters(), "ITEM").count
                        == slow.densest().get("ITEM")[0],
                "arithmetic shift on negatives is easy to get wrong");
    }

    static void testChunkZeroZero() {
        // The empty-slot sentinel must not collide with a real chunk key.
        WorldCensus census = new WorldCensus("world");
        for (int i = 0; i < 40; i++) {
            census.addEntity("ITEM", 5, 64, 5);
        }
        Snapshot.EntityCluster c = clusterFor(census.clusters(), "ITEM");
        check("chunk (0,0) is a real bucket, not an empty slot",
                c != null && c.count == 40,
                c == null ? "no cluster" : String.valueOf(c.count));
    }

    static void testHotspotIsActuallyTheDensest() {
        WorldCensus census = new WorldCensus("world");
        for (int i = 0; i < 200; i++) {
            census.addEntity("ITEM", 40, 64, 40);        // chunk (2,2)
        }
        for (int i = 0; i < 900; i++) {
            census.addEntity("ITEM", 412, 70, -1180);    // chunk (25,-74)
        }
        Snapshot.EntityCluster c = clusterFor(census.clusters(), "ITEM");
        check("the reported hotspot is the busiest chunk",
                c != null && c.count == 900 && c.x == 25 * 16 + 8
                        && c.z == -74 * 16 + 8,
                c == null ? "no cluster" : c.coords() + " n=" + c.count);
    }

    static void testAverageHeightIsReported() {
        WorldCensus census = new WorldCensus("world");
        census.addEntity("ITEM", 10, 60, 10);
        census.addEntity("ITEM", 11, 70, 11);
        Snapshot.EntityCluster c = clusterFor(census.clusters(), "ITEM");
        check("cluster height is the average of its members",
                c != null && c.y == 65, c == null ? "none" : "y=" + c.y);
    }

    static void testTypeCacheDoesNotLeakBetweenTypes() {
        // The same-type fast path must not attribute entities to the wrong type.
        WorldCensus census = new WorldCensus("world");
        census.addEntity("ITEM", 0, 64, 0);
        census.addEntity("ZOMBIE", 0, 64, 0);
        census.addEntity("ITEM", 0, 64, 0);
        Map<String, Integer> counts = census.entityCounts();
        check("alternating types are counted separately",
                counts.get("ITEM") == 2 && counts.get("ZOMBIE") == 1,
                counts.toString());
    }

    static void testFinishProducesUsableWorldStats() {
        WorldCensus census = new WorldCensus("nether");
        for (int i = 0; i < 300; i++) {
            census.addEntity("ITEM", 100 + i % 20, 64, 100);
        }
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("HOPPER", 12);
        Snapshot.WorldStats stats = census.finish(940, blocks);
        check("finish() yields consistent WorldStats",
                stats.name.equals("nether") && stats.loadedChunks == 940
                        && stats.entities("ITEM") == 300
                        && stats.blockEntities("HOPPER") == 12
                        && stats.densestCluster("ITEM") != null,
                stats.entityCountsByType.toString());
    }

    /**
     * On Folia each region thread counts its own chunks and the results are
     * merged. Splitting a population across several censuses and absorbing them
     * must give exactly the same answer as counting it all at once - otherwise
     * the plugin quietly reports different numbers depending on server flavour.
     */
    private static void splitAndMerge(String name, int entities, int shards,
                                      int spread, long seed) {
        String[] types = {"ITEM", "ZOMBIE", "ARROW", "COW"};
        Random random = new Random(seed);
        WorldCensus whole = new WorldCensus("world");
        List<WorldCensus> parts = new ArrayList<>();
        for (int i = 0; i < shards; i++) {
            parts.add(new WorldCensus("world"));
        }

        for (int i = 0; i < entities; i++) {
            String type = types[random.nextInt(types.length)];
            int x = random.nextInt(spread) - spread / 2;
            int y = random.nextInt(200) - 64;
            int z = random.nextInt(spread) - spread / 2;
            whole.addEntity(type, x, y, z);
            parts.get(random.nextInt(shards)).addEntity(type, x, y, z);
        }

        WorldCensus merged = new WorldCensus("world");
        for (WorldCensus part : parts) {
            merged.absorb(part);
        }

        check(name + ": merged totals equal the single-pass totals",
                merged.entityCounts().equals(whole.entityCounts()),
                merged.entityCounts() + " vs " + whole.entityCounts());

        boolean clustersMatch = true;
        String detail = "";
        for (Snapshot.EntityCluster expected : whole.clusters()) {
            Snapshot.EntityCluster got = clusterFor(merged.clusters(),
                    expected.type);
            if (got == null || got.count != expected.count) {
                clustersMatch = false;
                detail = expected.type + ": "
                        + (got == null ? "missing" : got.count + " != "
                        + expected.count);
                break;
            }
        }
        check(name + ": merged hotspots equal the single-pass hotspots",
                clustersMatch, detail);
    }

    static void testMergeAcrossTwoShards() {
        splitAndMerge("two shards", 5_000, 2, 4_000, 11L);
    }

    static void testMergeAcrossManyShards() {
        // More shards than a busy server would ever have regions.
        splitAndMerge("sixteen shards", 40_000, 16, 60_000, 12L);
    }

    static void testMergeWithDenseOverlap() {
        // Every shard writing into the same few chunks - maximum key collision
        // during the merge.
        splitAndMerge("dense overlap", 20_000, 8, 32, 13L);
    }

    static void testAbsorbIsSafeWithEmptyAndSelf() {
        WorldCensus census = new WorldCensus("world");
        for (int i = 0; i < 50; i++) {
            census.addEntity("ITEM", 10, 64, 10);
        }
        census.absorb(new WorldCensus("world"));
        census.absorb(null);
        census.absorb(census);
        check("absorbing empty, null or self changes nothing",
                census.entityCounts().get("ITEM") == 50,
                String.valueOf(census.entityCounts()));
    }

    static void testAbsorbKeepsCountingAfterwards() {
        // The per-type cache must not survive a merge and point at a stale
        // table.
        WorldCensus a = new WorldCensus("world");
        a.addEntity("ITEM", 0, 64, 0);
        WorldCensus b = new WorldCensus("world");
        b.addEntity("ITEM", 0, 64, 0);
        a.absorb(b);
        a.addEntity("ITEM", 0, 64, 0);
        check("a census still counts correctly after absorbing",
                a.entityCounts().get("ITEM") == 3
                        && clusterFor(a.clusters(), "ITEM").count == 3,
                String.valueOf(a.entityCounts()));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\nWorldCensus differential tests\n"
                + "----------------------------------------------------");
        List<java.lang.reflect.Method> tests = new ArrayList<>();
        for (java.lang.reflect.Method m : CensusTests.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) {
                tests.add(m);
            }
        }
        tests.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (java.lang.reflect.Method m : tests) {
            try {
                m.setAccessible(true);
                m.invoke(null);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(m.getName());
                System.out.println("  ERROR " + m.getName() + ": " + cause);
            }
        }
        System.out.println("----------------------------------------------------");
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("failed: " + String.join(", ", failures));
            System.exit(1);
        }
    }
}
