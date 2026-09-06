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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.ticktriage.core.remedy.EntityFacts;
import dev.ticktriage.core.remedy.RemediationAction;
import dev.ticktriage.core.remedy.RemediationPlan;
import dev.ticktriage.core.remedy.RemediationPlanner;
import dev.ticktriage.core.remedy.RemediationResult;
import dev.ticktriage.core.remedy.SafetyPolicy;

/**
 * Tests for the remediation layer.
 *
 * <p>{@code java dev.ticktriage.core.RemedyTests}
 *
 * <p>Most of these assert that the plugin <em>refuses</em> to act. This is the
 * only code in the project that destroys things, and the pitch against
 * LagFixer and ClearLag++ is precisely that it does not clear indiscriminately.
 * If these tests are weak, the product has no argument.
 */
public final class RemedyTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final long T0 = 1_757_000_000_000L;
    private static final long HEAP_MAX = 8L * 1024 * 1024 * 1024;

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    // --- fixtures ---------------------------------------------------------

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static Snapshot snap(long t, double ms, int players, int items,
                                 int zombies, int hoppers, boolean hotspot) {
        List<Snapshot.EntityCluster> clusters = hotspot
                ? Arrays.asList(
                        new Snapshot.EntityCluster("ITEM",
                                Math.max(1, (int) (items * 0.8)), 412, 64, -1180),
                        new Snapshot.EntityCluster("ZOMBIE",
                                Math.max(1, (int) (zombies * 0.8)), 200, 40, 200))
                : new ArrayList<Snapshot.EntityCluster>();
        Snapshot.WorldStats world = new Snapshot.WorldStats("world", 800,
                map("ITEM", items, "ZOMBIE", zombies, "VILLAGER", 12),
                map("HOPPER", hoppers, "CHEST", 150), clusters);
        return new Snapshot(t, ms, players, (long) (0.55 * HEAP_MAX), HEAP_MAX,
                5, 1000L, Arrays.asList(world));
    }

    private static History history(int items, int zombies, int hoppers,
                                   boolean hotspot) {
        History h = new History();
        for (int i = 0; i < 120; i++) {
            h.add(snap(T0 + i * 1000L, 40.0, 20, 180, 40, 120, hotspot));
        }
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 180.0, 20, items, zombies,
                    hoppers, hotspot));
        }
        return h;
    }

    private static RemediationPlan planFor(History h) {
        return new RemediationPlanner()
                .planFor(new DiagnosisEngine().analyse(h));
    }

    private static EntityFacts item(long ageTicks, int flags) {
        return new EntityFacts("ITEM", 100, 64, 100, ageTicks, flags);
    }

    // --- safety policy ----------------------------------------------------

    static void testPlainOldItemIsRemovable() {
        SafetyPolicy.Decision d = SafetyPolicy.defaults()
                .evaluate(item(6000, 0));
        check("an old, unremarkable dropped item may be removed", d.allowed,
                String.valueOf(d.reason));
    }

    static void testFreshDropsAreNeverTouched() {
        // A player who just died has their whole inventory on the ground.
        SafetyPolicy.Decision d = SafetyPolicy.defaults().evaluate(item(200, 0));
        check("items dropped seconds ago are protected",
                !d.allowed && d.reason.contains("less than"), String.valueOf(d.reason));
    }

    static void testMobsAreNotOnTheAllowlist() {
        SafetyPolicy.Decision d = SafetyPolicy.defaults().evaluate(
                new EntityFacts("ZOMBIE", 0, 64, 0, 100000, 0));
        check("mobs are never auto-removed", !d.allowed
                        && d.reason.contains("allowlist"), String.valueOf(d.reason));
    }

    static void testVillagersAreNotOnTheAllowlist() {
        SafetyPolicy.Decision d = SafetyPolicy.defaults().evaluate(
                new EntityFacts("VILLAGER", 0, 64, 0, 100000, 0));
        check("villagers are never auto-removed", !d.allowed);
    }

    static void testEveryProtectionFlagBlocks() {
        int[] flags = {EntityFacts.NAMED, EntityFacts.TAMED,
                EntityFacts.LEASHED, EntityFacts.IN_VEHICLE,
                EntityFacts.HAS_PASSENGERS, EntityFacts.PERSISTENT,
                EntityFacts.HAS_EQUIPMENT, EntityFacts.HAS_INVENTORY,
                EntityFacts.OWNED};
        String[] names = {"NAMED", "TAMED", "LEASHED", "IN_VEHICLE",
                "HAS_PASSENGERS", "PERSISTENT", "HAS_EQUIPMENT",
                "HAS_INVENTORY", "OWNED"};
        List<String> leaked = new ArrayList<>();
        for (int i = 0; i < flags.length; i++) {
            SafetyPolicy.Decision d = SafetyPolicy.defaults()
                    .evaluate(item(100000, flags[i]));
            if (d.allowed) {
                leaked.add(names[i]);
            }
        }
        check("every protection flag blocks removal", leaked.isEmpty(),
                "these did not block: " + leaked);
    }

    static void testOwnedItemsAreProtected() {
        // A dropped item reserved for a particular player to pick up. Found by
        // reading the real Bukkit API during live testing; it was not protected
        // before.
        SafetyPolicy.Decision d = SafetyPolicy.defaults()
                .evaluate(item(100000, EntityFacts.OWNED));
        check("an item reserved for a player is never removed",
                !d.allowed && d.reason.contains("reserved"),
                String.valueOf(d.reason));
    }

    static void testBlockedReasonsAreSpecific() {
        SafetyPolicy p = SafetyPolicy.defaults();
        check("block reasons name the actual protection",
                p.evaluate(item(100000, EntityFacts.NAMED)).reason
                        .contains("custom name")
                        && p.evaluate(item(100000, EntityFacts.TAMED)).reason
                        .contains("tamed"),
                "an admin has to be able to see why nothing happened");
    }

    static void testMinAgeIsConfigurable() {
        SafetyPolicy strict = SafetyPolicy.defaults().withMinAgeTicks(20 * 600);
        check("a stricter age threshold is honoured",
                !strict.evaluate(item(6000, 0)).allowed);
    }

    // --- planner ----------------------------------------------------------

    static void testHealthyServerYieldsNoPlan() {
        History h = new History();
        for (int i = 0; i < 120; i++) {
            h.add(snap(T0 + i * 1000L, 40.0, 20, 180, 40, 120, true));
        }
        check("a healthy server produces an empty plan",
                planFor(h).isEmpty());
    }

    static void testItemFloodProducesAnExecutableFix() {
        RemediationPlan plan = planFor(history(4207, 40, 120, true));
        List<RemediationAction> auto = plan.executable();
        check("an item flood with a hotspot yields an automatic fix",
                auto.size() == 1, plan.render());
        if (auto.size() == 1) {
            RemediationAction a = auto.get(0);
            check("the fix targets the right type and world",
                    "ITEM".equals(a.targetType) && "world".equals(a.world),
                    a.toString());
            check("the fix targets the hotspot chunk",
                    a.chunkX == (412 >> 4) && a.chunkZ == (-1180 >> 4),
                    "chunk (" + a.chunkX + ", " + a.chunkZ + ")");
        }
    }

    static void testItClearsTheExcessNotEverything() {
        // Baseline 180, peak 4207 -> the excess is 4027, and 180 must survive.
        RemediationAction a = planFor(history(4207, 40, 120, true))
                .executable().get(0);
        check("only the excess over baseline is cleared",
                a.maxToRemove == 4207 - 180,
                "maxToRemove=" + a.maxToRemove + ", expected " + (4207 - 180));
        check("the rationale states the excess explicitly",
                a.rationale.contains("4,027") && a.rationale.contains("180"),
                a.rationale);
    }

    static void testRadiusIsBoundedAroundTheHotspot() {
        RemediationAction a = planFor(history(4207, 40, 120, true))
                .executable().get(0);
        check("the fix is bounded to a small radius",
                a.radiusChunks == RemediationPlanner.DEFAULT_RADIUS_CHUNKS
                        && a.coversChunk(a.chunkX + 2, a.chunkZ)
                        && !a.coversChunk(a.chunkX + 3, a.chunkZ),
                "radius=" + a.radiusChunks);
    }

    static void testMobFloodIsAdviceOnly() {
        RemediationPlan plan = planFor(history(180, 4000, 120, true));
        check("a mob flood is never auto-cleared",
                plan.executable().isEmpty() && !plan.advice().isEmpty(),
                plan.render());
        boolean explains = false;
        for (RemediationAction a : plan.advice()) {
            if (a.rationale.contains("allowlist")) {
                explains = true;
            }
        }
        check("the mob refusal explains itself", explains, plan.render());
    }

    static void testHopperFloodIsAdviceOnly() {
        RemediationPlan plan = planFor(history(180, 40, 2400, true));
        boolean explains = false;
        for (RemediationAction a : plan.advice()) {
            if (a.rationale.contains("breaking blocks")) {
                explains = true;
            }
        }
        check("a hopper flood never breaks blocks",
                plan.executable().isEmpty() && explains, plan.render());
    }

    static void testNoHotspotMeansNoAutomaticClear() {
        RemediationPlan plan = planFor(history(4207, 40, 120, false));
        boolean explains = false;
        for (RemediationAction a : plan.advice()) {
            if (a.rationale.contains("indiscriminate")) {
                explains = true;
            }
        }
        check("without a hotspot it refuses to clear server-wide",
                plan.executable().isEmpty() && explains, plan.render());
    }

    static void testTinyExcessIsLeftAlone() {
        // 300 over a 180 baseline clears the detector but not the 100-excess
        // floor once baseline drift is accounted for; assert nothing executes
        // when the excess is below the planner's own minimum.
        RemediationPlanner planner = new RemediationPlanner();
        Diagnosis d = new Diagnosis("entity-flood", 0.9,
                Diagnosis.Severity.WARNING, "small flood", "advice")
                .withTarget(new DiagnosisTarget("world", "ITEM", false, true,
                        25, -74, 240, 180));
        RemediationAction a = planner.planFor(d);
        check("an excess below the floor is not worth touching the world for",
                a != null && !a.isExecutable()
                        && a.rationale.contains("not worth"),
                a == null ? "null" : a.toString());
    }

    static void testInfoDiagnosesProduceNothing() {
        RemediationPlanner planner = new RemediationPlanner();
        Diagnosis info = new Diagnosis("player-surge", 0.9,
                Diagnosis.Severity.INFO, "just busy", "nothing to do");
        check("an INFO diagnosis is never acted on",
                planner.planFor(info) == null);
    }

    static void testAdviceOnlyRulesStillSurfaceTheirFix() {
        RemediationPlanner planner = new RemediationPlanner();
        Diagnosis memory = new Diagnosis("memory-pressure", 0.9,
                Diagnosis.Severity.CRITICAL, "GC thrashing", "use Aikar's flags");
        RemediationAction a = planner.planFor(memory);
        check("a target-less diagnosis becomes advice carrying its fix",
                a != null && !a.isExecutable()
                        && a.rationale.contains("Aikar"),
                a == null ? "null" : a.toString());
    }

    static void testPlanRenderSeparatesAutomaticFromManual() {
        String text = planFor(history(4207, 4000, 2400, true)).render();
        check("the plan separates what it will do from what it won't",
                text.contains("Automatic fixes") && text.contains("Needs a human"),
                text);
    }

    // --- result -----------------------------------------------------------

    static void testResultRendersDryRunDistinctly() {
        RemediationResult r = new RemediationResult.Builder(true)
                .inspected(500).affected(300)
                .skipped("dropped less than 60s ago")
                .skipped("dropped less than 60s ago")
                .skipped("has a custom name")
                .build();
        String text = r.render();
        check("a dry run says nothing changed and how to apply",
                text.contains("Dry run") && text.contains("Would remove")
                        && text.contains("confirm"), text);
        check("skipped entities are grouped by reason with counts",
                text.contains("2 - dropped less than 60s ago")
                        && text.contains("1 - has a custom name"), text);
        check("total skipped is the sum of the reasons", r.totalSkipped() == 3);
    }

    static void testAppliedResultCarriesAnOperationId() {
        String text = new RemediationResult.Builder(false)
                .operationId("op-7").inspected(10).affected(4).build().render();
        check("an applied fix reports its operation id for undo",
                text.contains("Fix applied") && text.contains("op-7"), text);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\nRemediation tests\n"
                + "----------------------------------------------------");
        List<java.lang.reflect.Method> tests = new ArrayList<>();
        for (java.lang.reflect.Method m : RemedyTests.class.getDeclaredMethods()) {
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
