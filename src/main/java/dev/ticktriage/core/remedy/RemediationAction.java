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
package dev.ticktriage.core.remedy;

import dev.ticktriage.core.Stats;

/**
 * One step in a plan: either something the plugin will do, or something only a
 * human should do.
 *
 * <p>The split is deliberate and narrow. {@code CLEAR_ENTITIES} is the only
 * kind that touches the world, and it is always bounded to one entity type in
 * a small chunk radius with a hard cap. Everything else - mob floods, hopper
 * chains, memory, chunk loading - comes back as {@code ADVISE}, because the
 * fixes for those involve breaking blocks, killing animals, or editing configs,
 * and none of that should happen while nobody is watching.
 */
public final class RemediationAction {

    public enum Kind {
        CLEAR_ENTITIES,
        ADVISE
    }

    public final Kind kind;
    public final String world;
    public final String targetType;
    public final int chunkX;
    public final int chunkZ;
    public final int radiusChunks;
    /** Hard cap. The excess over this server's own baseline, never more. */
    public final int maxToRemove;
    public final String description;
    public final String rationale;

    private RemediationAction(Kind kind, String world, String targetType,
                              int chunkX, int chunkZ, int radiusChunks,
                              int maxToRemove, String description,
                              String rationale) {
        this.kind = kind;
        this.world = world;
        this.targetType = targetType;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.radiusChunks = radiusChunks;
        this.maxToRemove = maxToRemove;
        this.description = description;
        this.rationale = rationale;
    }

    public static RemediationAction clearEntities(String world, String type,
                                                  int chunkX, int chunkZ,
                                                  int radiusChunks,
                                                  int maxToRemove,
                                                  String rationale) {
        String description = "Clear up to " + Stats.formatCount(maxToRemove)
                + " " + type.toLowerCase().replace('_', ' ')
                + " entities in " + world + " within " + radiusChunks
                + " chunks of (" + chunkX + ", " + chunkZ + ")";
        return new RemediationAction(Kind.CLEAR_ENTITIES, world, type, chunkX,
                chunkZ, radiusChunks, maxToRemove, description, rationale);
    }

    public static RemediationAction advise(String description,
                                           String rationale) {
        return new RemediationAction(Kind.ADVISE, null, null, 0, 0, 0, 0,
                description, rationale);
    }

    public boolean isExecutable() {
        return kind == Kind.CLEAR_ENTITIES;
    }

    /** Chunk coordinates covered by this action, inclusive. */
    public boolean coversChunk(int x, int z) {
        return Math.abs(x - chunkX) <= radiusChunks
                && Math.abs(z - chunkZ) <= radiusChunks;
    }

    @Override
    public String toString() {
        return kind + ": " + description;
    }
}
