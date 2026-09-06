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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.ticktriage.core.Baseline;
import dev.ticktriage.core.Snapshot;
import dev.ticktriage.core.Stats;

/**
 * Too many entities of one type - the single most common cause of Minecraft
 * server lag, and the one owners most often misdiagnose as "we need more RAM".
 */
public final class EntityFloodRule extends FloodRule {

    private static final Set<String> ITEMS = new HashSet<>(Arrays.asList(
            "ITEM", "DROPPED_ITEM"));

    private static final Set<String> PROJECTILES = new HashSet<>(Arrays.asList(
            "ARROW", "SPECTRAL_ARROW", "SNOWBALL", "EGG", "TRIDENT",
            "FIREBALL", "SMALL_FIREBALL", "DRAGON_FIREBALL", "WITHER_SKULL",
            "SHULKER_BULLET", "LLAMA_SPIT"));

    private static final Set<String> STORAGE_CARTS = new HashSet<>(Arrays.asList(
            "MINECART", "CHEST_MINECART", "HOPPER_MINECART", "FURNACE_MINECART",
            "TNT_MINECART"));

    @Override
    public String id() {
        return "entity-flood";
    }

    @Override
    protected Map<String, Integer> counts(Snapshot.WorldStats world) {
        return world.entityCountsByType;
    }

    @Override
    protected double baselineFor(Baseline baseline, String world, String type) {
        return baseline.entities(world, type);
    }

    @Override
    protected double minAbsolute() {
        return 300.0;
    }

    @Override
    protected double minRatio() {
        return 2.0;
    }

    @Override
    protected double severeAbsolute() {
        return 4000.0;
    }

    @Override
    protected boolean isBlockEntity() {
        return false;
    }

    @Override
    protected String describe(String type, int count) {
        return Stats.formatCount(count) + " " + friendlyName(type);
    }

    private String friendlyName(String type) {
        if (ITEMS.contains(type)) {
            return "dropped items";
        }
        if ("EXPERIENCE_ORB".equals(type)) {
            return "experience orbs";
        }
        if ("ARMOR_STAND".equals(type)) {
            return "armour stands";
        }
        return type.toLowerCase().replace('_', ' ') + " entities";
    }

    @Override
    protected String adviceFor(String type, String world,
                               Snapshot.EntityCluster cluster) {
        String at = cluster == null ? "" : " near " + cluster.coords();

        if (ITEMS.contains(type)) {
            return "Almost always an unfiltered mob or item farm" + at
                    + ". Raise merge-radius.item in spigot.yml, shorten the item"
                    + " despawn rate for '" + world + "', or have staff inspect"
                    + " that build - a hopper-less farm output is the usual"
                    + " culprit.";
        }
        if ("EXPERIENCE_ORB".equals(type)) {
            return "An XP farm is dumping orbs faster than players collect them"
                    + at + ". Raise merge-radius.exp in spigot.yml.";
        }
        if (PROJECTILES.contains(type)) {
            return "Sustained projectile volume" + at + " usually means a"
                    + " dispenser or skeleton farm running unattended. Check for"
                    + " a redstone clock feeding it.";
        }
        if (STORAGE_CARTS.contains(type)) {
            return "Minecarts tick constantly even when idle" + at
                    + ". A parked storage-cart array is a common accidental"
                    + " lag machine - replace with chests or hoppers.";
        }
        if ("ARMOR_STAND".equals(type)) {
            return "Armour stands are often left behind by cosmetic or hologram"
                    + " plugins" + at + ". Confirm your hologram plugin is"
                    + " cleaning up, then remove the orphans.";
        }
        if ("VILLAGER".equals(type)) {
            return "Villager AI is disproportionately expensive" + at
                    + ". Cap breeders, or use a villager-optimisation plugin"
                    + " that disables pathfinding for stationary trades.";
        }
        return "Investigate what is producing " + friendlyName(type) + at
                + ". If it is an intended farm, cap it with per-chunk entity"
                + " limits in paper-world-defaults.yml.";
    }
}
