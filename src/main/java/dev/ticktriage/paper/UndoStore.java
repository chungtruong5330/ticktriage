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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps removed item stacks around so a mistaken fix can be reversed.
 *
 * <p>Undo exists for one scenario, and it is the scenario that would otherwise
 * end this plugin's reputation: the fix cleared something it should not have,
 * and an admin needs it back in the next few minutes. Restoring is capped and
 * in-memory - it does not survive a restart, and it is not a backup system.
 *
 * <p>Only item stacks are stored. Experience orbs are not restored: they carry
 * no identity worth recovering, and respawning thousands of them would recreate
 * the exact lag the fix just resolved.
 */
public final class UndoStore {

    public static final int DEFAULT_MAX_OPERATIONS = 5;
    public static final int DEFAULT_MAX_ENTRIES = 5000;

    private final Deque<Operation> operations = new ArrayDeque<>();
    private final int maxOperations;
    private final int maxEntries;
    private int counter = 0;

    public UndoStore() {
        this(DEFAULT_MAX_OPERATIONS, DEFAULT_MAX_ENTRIES);
    }

    public UndoStore(int maxOperations, int maxEntries) {
        this.maxOperations = Math.max(1, maxOperations);
        this.maxEntries = Math.max(0, maxEntries);
    }

    public synchronized Operation begin(String world) {
        Operation op = new Operation("op-" + (++counter), world, maxEntries);
        operations.addLast(op);
        while (operations.size() > maxOperations) {
            operations.removeFirst();
        }
        return op;
    }

    public synchronized Operation latest() {
        return operations.isEmpty() ? null : operations.getLast();
    }

    public synchronized Operation byId(String id) {
        for (Operation op : operations) {
            if (op.id.equals(id)) {
                return op;
            }
        }
        return null;
    }

    public synchronized List<Operation> all() {
        return new ArrayList<>(operations);
    }

    public synchronized void forget(Operation op) {
        operations.remove(op);
    }

    /**
     * Drops the stored stacks back where they came from.
     *
     * <p>Grouped by chunk and scheduled per chunk, because on Folia an item may
     * only be spawned by the thread that owns its region. The callback receives
     * how many were restored, or -1 if the world is gone.
     */
    public void restore(Platform platform, Operation op,
                        Consumer<Integer> onComplete) {
        World world = Bukkit.getWorld(op.world);
        if (world == null) {
            onComplete.accept(-1);
            return;
        }

        Map<Long, List<Entry>> byChunk = new LinkedHashMap<>();
        for (Entry entry : op.entries) {
            long key = (((long) (entry.x >> 4)) << 32)
                    | ((entry.z >> 4) & 0xffffffffL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
        if (byChunk.isEmpty()) {
            forget(op);
            onComplete.accept(0);
            return;
        }

        AtomicInteger restored = new AtomicInteger();
        AtomicInteger outstanding = new AtomicInteger(byChunk.size());

        for (Map.Entry<Long, List<Entry>> group : byChunk.entrySet()) {
            int chunkX = (int) (group.getKey() >> 32);
            int chunkZ = (int) (long) group.getKey();
            List<Entry> entries = group.getValue();
            platform.runAtChunk(world, chunkX, chunkZ, () -> {
                for (Entry entry : entries) {
                    try {
                        world.dropItem(new Location(world, entry.x + 0.5,
                                entry.y, entry.z + 0.5), entry.stack);
                        restored.incrementAndGet();
                    } catch (Throwable t) {
                        // One bad stack must not abandon the rest.
                    }
                }
                if (outstanding.decrementAndGet() == 0) {
                    platform.runGlobal(() -> {
                        forget(op);
                        onComplete.accept(restored.get());
                    });
                }
            });
        }
    }

    /** One applied fix, and what it removed. */
    public static final class Operation {

        public final String id;
        public final String world;
        public final long createdAtMillis = System.currentTimeMillis();

        private final List<Entry> entries = new ArrayList<>();
        private final int maxEntries;
        private int dropped = 0;

        Operation(String id, String world, int maxEntries) {
            this.id = id;
            this.world = world;
            this.maxEntries = maxEntries;
        }

        void record(ItemStack stack, int x, int y, int z) {
            if (stack == null) {
                return;
            }
            if (entries.size() >= maxEntries) {
                dropped++;
                return;
            }
            entries.add(new Entry(stack.clone(), x, y, z));
        }

        public int restorable() {
            return entries.size();
        }

        /** Removals past the cap, which cannot be undone. */
        public int notRestorable() {
            return dropped;
        }

        public long ageSeconds() {
            return (System.currentTimeMillis() - createdAtMillis) / 1000L;
        }
    }

    private static final class Entry {
        final ItemStack stack;
        final int x;
        final int y;
        final int z;

        Entry(ItemStack stack, int x, int y, int z) {
            this.stack = stack;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
