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
package dev.ticktriage.core.remedy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Protection an admin declares by hand: whole worlds, or explicit boxes.
 *
 * <p>This exists so the safety net does not require a claim plugin. Plenty of
 * servers protect spawn by convention rather than with WorldGuard, and "never
 * touch anything in the creative world" is a sentence an owner can write in a
 * config far more easily than a region definition.
 */
public final class ConfiguredProtection implements ProtectionOracle {

    private final Set<String> worlds;
    private final List<Box> boxes;

    public ConfiguredProtection(Set<String> protectedWorlds, List<Box> boxes) {
        Set<String> lower = new HashSet<>();
        for (String w : protectedWorlds) {
            lower.add(w.toLowerCase(Locale.ROOT));
        }
        this.worlds = Collections.unmodifiableSet(lower);
        this.boxes = Collections.unmodifiableList(new ArrayList<>(boxes));
    }

    public static ProtectionOracle of(Set<String> protectedWorlds,
                                      List<Box> boxes) {
        if (protectedWorlds.isEmpty() && boxes.isEmpty()) {
            return ProtectionOracle.NONE;
        }
        return new ConfiguredProtection(protectedWorlds, boxes);
    }

    public boolean isEmpty() {
        return worlds.isEmpty() && boxes.isEmpty();
    }

    @Override
    public boolean isProtected(String world, int x, int y, int z) {
        if (world != null && worlds.contains(world.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (Box box : boxes) {
            if (box.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "config (" + worlds.size() + " worlds, " + boxes.size()
                + " regions)";
    }

    /** An axis-aligned box, inclusive on both corners. */
    public static final class Box {

        public final String world;
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxX;
        public final int maxY;
        public final int maxZ;

        public Box(String world, int x1, int y1, int z1, int x2, int y2,
                   int z2) {
            this.world = world;
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        public boolean contains(String w, int x, int y, int z) {
            return world.equalsIgnoreCase(w)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }
}
