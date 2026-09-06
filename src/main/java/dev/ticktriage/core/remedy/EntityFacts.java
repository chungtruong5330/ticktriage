package dev.ticktriage.core.remedy;

/**
 * Everything the safety policy needs to know about one entity, as plain data.
 *
 * <p>The platform layer builds these from Bukkit entities; the policy that
 * decides what may be removed lives in {@code core} and never sees Bukkit. That
 * split is what lets the safety rules - the part that can destroy a player's
 * belongings - be tested exhaustively without a server.
 *
 * <p>Flags are a bitset rather than a dozen boolean parameters because this is
 * constructed per candidate entity during a fix.
 */
public final class EntityFacts {

    public static final int NAMED = 1;
    public static final int TAMED = 1 << 1;
    public static final int LEASHED = 1 << 2;
    public static final int IN_VEHICLE = 1 << 3;
    public static final int HAS_PASSENGERS = 1 << 4;
    public static final int PERSISTENT = 1 << 5;
    public static final int HAS_EQUIPMENT = 1 << 6;
    public static final int HAS_INVENTORY = 1 << 7;

    public final String type;
    public final int x;
    public final int y;
    public final int z;
    /** Ticks the entity has existed. 20 ticks is one second. */
    public final long ageTicks;
    public final int flags;

    public EntityFacts(String type, int x, int y, int z, long ageTicks,
                       int flags) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.ageTicks = ageTicks;
        this.flags = flags;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public static int flags(boolean named, boolean tamed, boolean leashed,
                           boolean inVehicle, boolean hasPassengers,
                           boolean persistent, boolean hasEquipment,
                           boolean hasInventory) {
        int f = 0;
        if (named) {
            f |= NAMED;
        }
        if (tamed) {
            f |= TAMED;
        }
        if (leashed) {
            f |= LEASHED;
        }
        if (inVehicle) {
            f |= IN_VEHICLE;
        }
        if (hasPassengers) {
            f |= HAS_PASSENGERS;
        }
        if (persistent) {
            f |= PERSISTENT;
        }
        if (hasEquipment) {
            f |= HAS_EQUIPMENT;
        }
        if (hasInventory) {
            f |= HAS_INVENTORY;
        }
        return f;
    }
}
