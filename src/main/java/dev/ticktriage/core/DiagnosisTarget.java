package dev.ticktriage.core;

/**
 * The machine-readable half of a diagnosis.
 *
 * <p>A rule's prose tells a human what happened. This tells the remediation
 * planner exactly what and where, so a fix can be aimed at one chunk of one
 * world rather than sprayed across the server.
 *
 * <p>{@link #excess()} is the number that matters: how far above this server's
 * own normal the count sits. Remediation clears the excess and stops, which is
 * the whole difference between this and every "clear all items" plugin.
 */
public final class DiagnosisTarget {

    public final String world;
    public final String type;
    /** True when {@link #type} names a block entity (hopper, spawner) rather
     *  than a mob or item - block entities are never auto-removed. */
    public final boolean blockEntity;

    public final boolean hasHotspot;
    public final int chunkX;
    public final int chunkZ;

    public final int observed;
    public final int baseline;

    public DiagnosisTarget(String world, String type, boolean blockEntity,
                           boolean hasHotspot, int chunkX, int chunkZ,
                           int observed, int baseline) {
        this.world = world;
        this.type = type;
        this.blockEntity = blockEntity;
        this.hasHotspot = hasHotspot;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.observed = observed;
        this.baseline = baseline;
    }

    /** How many above normal. Never negative. */
    public int excess() {
        return Math.max(0, observed - baseline);
    }

    public String describeLocation() {
        if (!hasHotspot) {
            return "world '" + world + "'";
        }
        return "world '" + world + "' chunk (" + chunkX + ", " + chunkZ + ")";
    }

    @Override
    public String toString() {
        return type + "@" + describeLocation() + " " + observed + "/" + baseline;
    }
}
