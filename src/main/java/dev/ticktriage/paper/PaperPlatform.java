package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Ordinary Paper: one main thread, so every scheduling variant is the same
 * thing and every chunk is owned by whoever is on it.
 */
public final class PaperPlatform implements Platform {

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public PaperPlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Paper";
    }

    @Override
    public boolean isFolia() {
        return false;
    }

    @Override
    public void runRepeating(Runnable task, long delayTicks, long periodTicks) {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks,
                periodTicks));
    }

    /**
     * Run now if we are already on the main thread, otherwise schedule.
     *
     * <p>{@code runTask} always defers to the <em>next</em> tick, even when
     * called from the main thread. That silently broke {@code /ticktriage fix}
     * over RCON and the console: the command handler had already returned its
     * output by the time the result arrived a tick later, so the dry run
     * reported nothing at all. Found by running it on a real server.
     */
    private void runNowOrSoon(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runGlobal(Runnable task) {
        runNowOrSoon(task);
    }

    @Override
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        runNowOrSoon(task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        runNowOrSoon(task);
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void cancelAll() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }
}
