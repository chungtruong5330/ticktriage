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

import dev.ticktriage.core.remedy.EntityFacts;
import dev.ticktriage.core.remedy.RemediationAction;
import dev.ticktriage.core.remedy.RemediationPlan;
import dev.ticktriage.core.remedy.RemediationPlanner;
import dev.ticktriage.core.remedy.RemediationResult;
import dev.ticktriage.core.remedy.SafetyPolicy;

/**
 * Prints what an owner actually sees, for three realistic scenarios.
 *
 * <p>{@code java dev.ticktriage.core.Demo} - no server required. This doubles as
 * the material for a store listing: the output is the product.
 */
public final class Demo {

    private static final long T0 = 1_757_000_000_000L;
    private static final long HEAP_MAX = 8L * 1024 * 1024 * 1024;

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static Snapshot snap(long t, double ms, int players, int items,
                                 int hoppers, int chunks, double heap, long gc) {
        Snapshot.WorldStats overworld = new Snapshot.WorldStats(
                "world", chunks,
                map("ITEM", items, "ZOMBIE", 62, "VILLAGER", 28),
                map("HOPPER", hoppers, "CHEST", 210),
                Arrays.asList(
                        new Snapshot.EntityCluster("ITEM",
                                Math.max(1, (int) (items * 0.82)), 412, 64, -1180),
                        new Snapshot.EntityCluster("HOPPER",
                                Math.max(1, (int) (hoppers * 0.74)), -220, 12, 640)));
        Snapshot.WorldStats nether = new Snapshot.WorldStats(
                "world_nether", 190,
                map("ITEM", 40, "PIGLIN", 30),
                map("HOPPER", 12),
                Arrays.asList(new Snapshot.EntityCluster("ITEM", 32, 55, 70, 18)));
        return new Snapshot(t, ms, players, (long) (heap * HEAP_MAX), HEAP_MAX,
                gc, 1000L, Arrays.asList(overworld, nether));
    }

    private static History calm(int samples) {
        History h = new History();
        for (int i = 0; i < samples; i++) {
            h.add(snap(T0 + i * 1000L, 41.0, 34, 190, 130, 860, 0.58, 6));
        }
        return h;
    }

    private static void show(String title, History h) {
        System.out.println("\n===== " + title + " "
                + "=====================================".substring(
                        Math.min(title.length(), 30)));
        System.out.println(new DiagnosisEngine().analyse(h).render());
    }

    /**
     * Walks a synthetic entity population through the real safety policy and
     * builds the result exactly as the executor does. Only the Bukkit
     * iteration is stubbed - every decision here is production code.
     */
    private static void showRemediation(History history) {
        Report report = new DiagnosisEngine().analyse(history);
        RemediationPlanner planner = new RemediationPlanner();
        RemediationPlan plan = planner.planFor(report);

        System.out.println("\n----- what it proposes -----");
        System.out.println(plan.render());

        if (!plan.hasExecutableActions()) {
            return;
        }
        RemediationAction action = plan.executable().get(0);

        // A believable population in the hotspot: mostly old farm output, some
        // just-dropped, a couple somebody named, one in a minecart.
        List<EntityFacts> population = new ArrayList<>();
        for (int i = 0; i < 4400; i++) {
            population.add(new EntityFacts("ITEM", 412, 64, -1180, 9000, 0));
        }
        for (int i = 0; i < 260; i++) {
            population.add(new EntityFacts("ITEM", 415, 64, -1176, 300, 0));
        }
        for (int i = 0; i < 3; i++) {
            population.add(new EntityFacts("ITEM", 410, 64, -1182, 9000,
                    EntityFacts.NAMED));
        }
        population.add(new EntityFacts("ITEM", 409, 64, -1183, 9000,
                EntityFacts.HAS_INVENTORY));

        RemediationResult.Builder result = new RemediationResult.Builder(true);
        int eligible = 0;
        for (EntityFacts entity : population) {
            result.inspected(1);
            SafetyPolicy.Decision decision = planner.policy().evaluate(entity);
            if (decision.allowed) {
                eligible++;
            } else {
                result.skipped(decision.reason);
            }
        }
        int wouldRemove = Math.min(eligible, action.maxToRemove);
        result.affected(wouldRemove);
        if (eligible > wouldRemove) {
            result.note((eligible - wouldRemove) + " eligible items left in"
                    + " place - the plan only clears the excess over normal.");
        }

        System.out.println("\n----- dry run -----");
        System.out.println(result.build().render());
    }

    public static void main(String[] args) {
        History flood = calm(150);
        for (int i = 0; i < 7; i++) {
            flood.add(snap(T0 + (150 + i) * 1000L, 181.0, 36, 4207, 130, 870,
                    0.60, 7));
        }
        show("Scenario 1: item farm left running", flood);
        showRemediation(flood);

        History gc = calm(150);
        for (int i = 0; i < 7; i++) {
            gc.add(snap(T0 + (150 + i) * 1000L, 240.0, 35, 200, 130, 880,
                    0.97, 520));
        }
        show("Scenario 2: heap thrashing", gc);

        show("Scenario 3: nothing wrong", calm(150));
    }
}
