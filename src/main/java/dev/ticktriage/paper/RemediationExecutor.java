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
package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import dev.ticktriage.core.remedy.EntityFacts;
import dev.ticktriage.core.remedy.RemediationAction;
import dev.ticktriage.core.remedy.RemediationPlan;
import dev.ticktriage.core.remedy.RemediationResult;
import dev.ticktriage.core.remedy.RemovalFilter;
import dev.ticktriage.core.remedy.SafetyPolicy;

/**
 * Carries out a plan against the live server.
 *
 * <p>Every decision about <em>what may be destroyed</em> lives in
 * {@link RemovalFilter} in the core package, where it is exhaustively tested.
 * This class only supplies the facts and does the removing.
 *
 * <p>Three behaviours worth knowing:
 *
 * <ul>
 *   <li>The work runs on the thread that owns the target chunks. On Paper that
 *       is the main thread; on Folia it is the owning region's thread, and
 *       touching those entities from anywhere else would be undefined.</li>
 *   <li>Only <b>loaded</b> chunks are touched. Loading a chunk to clean it
 *       would create the very work the fix is meant to save.</li>
 *   <li>Candidates are removed <b>oldest first</b>. If a cap is hit, what
 *       survives is the most recently dropped - the material most likely to
 *       belong to a player still standing there.</li>
 * </ul>
 */
public final class RemediationExecutor {

    private final RemovalFilter filter;
    private final UndoStore undo;
    private final Platform platform;

    public RemediationExecutor(RemovalFilter filter, UndoStore undo,
                               Platform platform) {
        this.filter = filter;
        this.undo = undo;
        this.platform = platform;
    }

    /**
     * Applies the plan. The callback fires inline on Paper, and on the global
     * thread once every region has reported on Folia.
     */
    public void execute(RemediationPlan plan, boolean dryRun,
                        Consumer<RemediationResult> onComplete) {
        final RemediationResult.Builder result =
                new RemediationResult.Builder(dryRun);
        List<RemediationAction> actions = plan.executable();

        if (actions.isEmpty()) {
            onComplete.accept(
                    result.note("No automatic fixes in this plan.").build());
            return;
        }

        final UndoStore.Operation operation =
                dryRun ? null : undo.begin(actions.get(0).world);
        if (operation != null) {
            result.operationId(operation.id);
        }

        final AtomicInteger outstanding = new AtomicInteger(actions.size());
        final Runnable finish = () -> {
            if (outstanding.decrementAndGet() > 0) {
                return;
            }
            if (operation != null) {
                synchronized (result) {
                    if (operation.notRestorable() > 0) {
                        result.note(operation.notRestorable() + " removals"
                                + " exceeded the undo cap and cannot be"
                                + " restored.");
                    }
                    if (operation.restorable() > 0) {
                        result.note("Undo with /ticktriage undo " + operation.id
                                + " while the server stays up.");
                    }
                }
            }
            RemediationResult built;
            synchronized (result) {
                built = result.build();
            }
            platform.runGlobal(() -> onComplete.accept(built));
        };

        for (RemediationAction action : actions) {
            World world = Bukkit.getWorld(action.world);
            if (world == null) {
                synchronized (result) {
                    result.note("World '" + action.world + "' is not loaded.");
                }
                finish.run();
                continue;
            }
            platform.runAtChunk(world, action.chunkX, action.chunkZ, () -> {
                try {
                    applyClear(world, action, dryRun, result, operation);
                } finally {
                    finish.run();
                }
            });
        }
    }

    private void applyClear(World world, RemediationAction action,
                            boolean dryRun, RemediationResult.Builder result,
                            UndoStore.Operation operation) {
        List<Entity> candidates = new ArrayList<>();
        int inspected = 0;
        List<String> skipReasons = new ArrayList<>();

        // Walk the target box directly rather than every loaded chunk in the
        // world: cheaper everywhere, and on Folia it is the only correct way,
        // since a chunk outside this region belongs to another thread.
        for (int cx = action.chunkX - action.radiusChunks;
                cx <= action.chunkX + action.radiusChunks; cx++) {
            for (int cz = action.chunkZ - action.radiusChunks;
                    cz <= action.chunkZ + action.radiusChunks; cz++) {
                if (!platform.ownsChunk(world, cx, cz)
                        || !world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (Entity entity : chunk.getEntities()) {
                    if (!entity.getType().name().equals(action.targetType)) {
                        continue;
                    }
                    inspected++;
                    SafetyPolicy.Decision decision =
                            filter.evaluate(world.getName(), factsFor(entity));
                    if (!decision.allowed) {
                        skipReasons.add(decision.reason);
                        continue;
                    }
                    candidates.add(entity);
                }
            }
        }

        // Oldest first, so a cap spares the freshest drops.
        candidates.sort(Comparator.comparingInt(Entity::getTicksLived).reversed());

        int removed = 0;
        for (Entity entity : candidates) {
            if (removed >= action.maxToRemove) {
                break;
            }
            if (!dryRun) {
                if (operation != null && entity instanceof Item) {
                    Location loc = entity.getLocation();
                    operation.record(((Item) entity).getItemStack(),
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                }
                entity.remove();
            }
            removed++;
        }

        int spared = candidates.size() - removed;
        synchronized (result) {
            result.inspected(inspected);
            for (String reason : skipReasons) {
                result.skipped(reason);
            }
            result.affected(removed);
            if (spared > 0) {
                result.note(spared + " eligible "
                        + action.targetType.toLowerCase() + " entities left in"
                        + " place - the plan only clears the excess over"
                        + " normal.");
            }
        }
    }

    /** Translate a Bukkit entity into the plain facts the policy understands. */
    private EntityFacts factsFor(Entity entity) {
        boolean named = entity.customName() != null;
        boolean tamed = entity instanceof Tameable
                && ((Tameable) entity).isTamed();
        boolean leashed = entity instanceof LivingEntity
                && ((LivingEntity) entity).isLeashed();
        boolean inVehicle = entity.isInsideVehicle();
        boolean hasPassengers = !entity.getPassengers().isEmpty();
        boolean hasEquipment = hasEquipment(entity);
        boolean hasInventory = entity instanceof InventoryHolder;
        boolean persistent = deliberatelyPermanent(entity);
        boolean owned = entity instanceof Item
                && ((Item) entity).getOwner() != null;

        Location loc = entity.getLocation();
        return new EntityFacts(entity.getType().name(), loc.getBlockX(),
                loc.getBlockY(), loc.getBlockZ(), entity.getTicksLived(),
                EntityFacts.flags(named, tamed, leashed, inVehicle,
                        hasPassengers, persistent, hasEquipment, hasInventory,
                        owned));
    }

    /**
     * Whether somebody deliberately made this entity permanent.
     *
     * <p><b>Not</b> {@code Entity#isPersistent()}. That means "gets saved to the
     * world file" and is true for virtually every entity, including ordinary
     * dropped items - using it as a veto made remediation a no-op that removed
     * nothing and reported "32,061 skipped: is marked persistent". Found by
     * running an actual fix on an actual server.
     *
     * <p>The real signals are per-type: an item with unlimited lifetime was set
     * that way on purpose, and a mob that will not despawn when far away was
     * kept on purpose.
     */
    private boolean deliberatelyPermanent(Entity entity) {
        if (entity instanceof Item) {
            Item item = (Item) entity;
            return item.isUnlimitedLifetime() || !item.willAge();
        }
        if (entity instanceof LivingEntity) {
            return !((LivingEntity) entity).getRemoveWhenFarAway();
        }
        return false;
    }

    private boolean hasEquipment(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        EntityEquipment equipment = ((LivingEntity) entity).getEquipment();
        if (equipment == null) {
            return false;
        }
        for (ItemStack stack : equipment.getArmorContents()) {
            if (stack != null && !stack.getType().isAir()) {
                return true;
            }
        }
        ItemStack main = equipment.getItemInMainHand();
        ItemStack off = equipment.getItemInOffHand();
        return (main != null && !main.getType().isAir())
                || (off != null && !off.getType().isAir());
    }
}
