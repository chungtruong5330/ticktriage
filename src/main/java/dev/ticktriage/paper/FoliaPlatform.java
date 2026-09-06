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

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Folia: regionised multithreading, so tasks have to be aimed at the thread
 * that owns the data they touch.
 *
 * <p>These scheduler types ship in the Paper API itself, so this class compiles
 * against ordinary paper-api and is simply never instantiated on a server that
 * is not Folia.
 */
public final class FoliaPlatform implements Platform {

    private final Plugin plugin;
    private final List<ScheduledTask> tasks = new ArrayList<>();

    public FoliaPlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Folia";
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    @Override
    public void runRepeating(Runnable task, long delayTicks, long periodTicks) {
        tasks.add(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                scheduled -> task.run(), Math.max(1L, delayTicks),
                Math.max(1L, periodTicks)));
    }

    @Override
    public void runGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        // The entity scheduler follows the entity if it crosses a region
        // boundary before the task fires, which the region scheduler would not.
        entity.getScheduler().run(plugin, scheduled -> task.run(), retired);
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return Bukkit.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public void cancelAll() {
        for (ScheduledTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }
}
