package dev.ticktriage.paper;

import org.bukkit.plugin.Plugin;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

/** Picks the right {@link Platform} for whatever server this is. */
public final class Platforms {

    private Platforms() {
    }

    public static Platform detect(Plugin plugin) {
        return isFolia() ? new FoliaPlatform(plugin) : new PaperPlatform(plugin);
    }

    /**
     * Brand check first, as PaperMC recommends. The class probe is a fallback
     * for forks that report a different brand but still regionise - getting
     * this wrong means using the Bukkit scheduler on Folia, which fails at
     * startup rather than quietly, but there is no reason to rely on that.
     */
    public static boolean isFolia() {
        try {
            if (ServerBuildInfo.buildInfo()
                    .isBrandCompatible(Key.key("papermc", "folia"))) {
                return true;
            }
        } catch (Throwable ignored) {
            // Older or unusual server; fall through to the class probe.
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
