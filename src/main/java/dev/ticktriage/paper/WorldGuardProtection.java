package dev.ticktriage.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;

import dev.ticktriage.core.remedy.ProtectionOracle;

/**
 * Treats any location inside a WorldGuard region as off-limits.
 *
 * <p>Deliberately crude: <em>any</em> region counts, regardless of its flags.
 * Reading flags properly would mean deciding whether "this region denies block
 * breaking" implies "do not clear dropped items here", and getting that
 * inference wrong deletes somebody's things. A region existing at all is a
 * clear enough signal that a human has claimed the spot.
 */
public final class WorldGuardProtection implements ProtectionOracle {

    private final RegionContainer container;

    private WorldGuardProtection(RegionContainer container) {
        this.container = container;
    }

    /** @return the oracle, or null if WorldGuard is absent or unusable. */
    public static WorldGuardProtection tryCreate() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            return null;
        }
        RegionContainer container = WorldGuard.getInstance().getPlatform()
                .getRegionContainer();
        if (container == null) {
            throw new IllegalStateException(
                    "WorldGuard is present but has no region container");
        }
        return new WorldGuardProtection(container);
    }

    @Override
    public boolean isProtected(String world, int x, int y, int z) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            // Cannot check, so assume claimed.
            return true;
        }
        Location location = new Location(bukkitWorld, x, y, z);
        return container.createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(location))
                .size() > 0;
    }

    @Override
    public String describe() {
        return "WorldGuard";
    }
}
