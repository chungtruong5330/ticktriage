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
package dev.ticktriage.core.rules;

import java.util.Map;

import dev.ticktriage.core.Baseline;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * Too many ticking block entities. Hoppers are the classic offender: each one
 * checks for items every few ticks whether or not anything is moving, so a
 * storage build can cost an owner real TPS while looking completely idle.
 */
public final class BlockEntityFloodRule extends FloodRule {

    @Override
    public String id() {
        return "block-entity-flood";
    }

    @Override
    protected Map<String, Integer> counts(Snapshot.WorldStats world) {
        return world.blockEntityCountsByType;
    }

    @Override
    protected double baselineFor(Baseline baseline, String world, String type) {
        return baseline.blockEntities(world, type);
    }

    @Override
    protected double minAbsolute() {
        return 200.0;
    }

    @Override
    protected double minRatio() {
        return 1.8;
    }

    @Override
    protected double severeAbsolute() {
        return 2500.0;
    }

    @Override
    protected boolean isBlockEntity() {
        return true;
    }

    @Override
    protected String describe(String type, int count) {
        return Stats.formatCount(count) + " "
                + type.toLowerCase().replace('_', ' ') + " blocks";
    }

    @Override
    protected String adviceFor(String type, String world,
                               Snapshot.EntityCluster cluster) {
        String at = cluster == null ? "" : " near " + cluster.coords();

        if ("HOPPER".equals(type)) {
            return "Hoppers poll for items constantly, even empty ones" + at
                    + ". Raise ticks-per.hopper-transfer in spigot.yml, or ask"
                    + " the builder to replace long hopper chains with water"
                    + " streams or droppers.";
        }
        if ("CHEST".equals(type) || "TRAPPED_CHEST".equals(type)) {
            return "Large chest arrays" + at + " are cheap individually but add"
                    + " up. Usually harmless on its own - worth checking only if"
                    + " it coincides with hopper load.";
        }
        if ("SPAWNER".equals(type)) {
            return "Stacked spawners" + at + " tick constantly and spawn"
                    + " entities. Cap spawner stacks, or reduce their activation"
                    + " range in paper-world-defaults.yml.";
        }
        if (type.contains("FURNACE") || "BLAST_FURNACE".equals(type)
                || "SMOKER".equals(type)) {
            return "A large smelting array" + at + " is ticking. Normally fine,"
                    + " but combined with hoppers it compounds - check whether"
                    + " they are hopper-fed.";
        }
        return "An unusual number of " + type.toLowerCase().replace('_', ' ')
                + " blocks is ticking" + at + ". Inspect that build and confirm"
                + " it is intentional.";
    }
}
