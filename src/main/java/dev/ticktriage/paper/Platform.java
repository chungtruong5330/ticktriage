package dev.ticktriage.paper;

import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * The scheduling differences between Paper and Folia, behind one interface.
 *
 * <p>On Paper everything runs on one main thread and all of these collapse to
 * the Bukkit scheduler. On Folia the world is split into regions that tick on
 * separate threads, and touching a chunk or entity from the wrong thread is
 * undefined behaviour rather than a clean exception - so <em>where</em> a task
 * runs is a correctness question, not a performance one.
 */
public interface Platform {

    String name();

    boolean isFolia();

    /** A repeating task with no particular location - stats, analysis, alerts. */
    void runRepeating(Runnable task, long delayTicks, long periodTicks);

    /** One-off task on the global tick thread. */
    void runGlobal(Runnable task);

    /**
     * Runs on whichever thread owns that chunk, which is the only thread
     * allowed to look at the entities in it.
     */
    void runAtChunk(World world, int chunkX, int chunkZ, Runnable task);

    /**
     * Runs on the thread owning {@code entity}, following it if it moves
     * between regions before the task fires.
     *
     * @param retired invoked instead if the entity vanished before it ran
     */
    void runForEntity(Entity entity, Runnable task, Runnable retired);

    /** Whether the calling thread may touch that chunk right now. */
    boolean ownsChunk(World world, int chunkX, int chunkZ);

    void cancelAll();
}
