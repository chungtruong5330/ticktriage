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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;

import dev.ticktriage.core.remedy.ProtectionOracle;

/**
 * Treats any location inside a GriefPrevention claim as off-limits.
 *
 * <p>Height is ignored on purpose, so a claim protects its whole column. A farm
 * built above somebody's claimed base is still that person's problem to sort
 * out, not this plugin's to clear.
 */
public final class GriefPreventionProtection implements ProtectionOracle {

    /** GriefPrevention caches the last claim looked up; reusing it makes
     *  repeated queries in one chunk much cheaper. */
    private Claim cached;

    private GriefPreventionProtection() {
    }

    /** @return the oracle, or null if GriefPrevention is absent or unusable. */
    public static GriefPreventionProtection tryCreate() {
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") == null) {
            return null;
        }
        if (GriefPrevention.instance == null
                || GriefPrevention.instance.dataStore == null) {
            throw new IllegalStateException(
                    "GriefPrevention is present but not initialised");
        }
        return new GriefPreventionProtection();
    }

    @Override
    public boolean isProtected(String world, int x, int y, int z) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return true;
        }
        Location location = new Location(bukkitWorld, x, y, z);
        Claim claim = GriefPrevention.instance.dataStore
                .getClaimAt(location, true, cached);
        if (claim != null) {
            cached = claim;
            return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return "GriefPrevention";
    }
}
