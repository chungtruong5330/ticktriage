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

    @Override
    public void runGlobal(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        Bukkit.getScheduler().runTask(plugin, task);
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
