package dev.ticktriage.core.remedy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides whether a single entity may be removed. Deny by default.
 *
 * <p>This is the highest-consequence code in the plugin. Every other bug costs
 * a bad reading; a bug here destroys something a player owned, and no review
 * recovers from that. So it is built as an allowlist with veto flags: an entity
 * is removable only if its type is explicitly permitted <em>and</em> nothing
 * about it suggests a person cares.
 *
 * <p>Mobs are deliberately absent from the default allowlist. Killing mobs is
 * what the blunt cleanup plugins do, and it is how they end up deleting a
 * player's bred animals. TickTriage diagnoses mob floods and advises; it does
 * not silently cull them.
 */
public final class SafetyPolicy {

    /**
     * Only these can ever be auto-removed. Items and orbs regenerate; arrows
     * are litter. Everything else is somebody's.
     */
    private static final Set<String> DEFAULT_REMOVABLE = Collections
            .unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "ITEM", "DROPPED_ITEM", "EXPERIENCE_ORB",
                    "ARROW", "SPECTRAL_ARROW", "SNOWBALL", "EGG")));

    /**
     * Sixty seconds. A player who just died has their entire inventory on the
     * ground, and clearing it would be the single worst thing this plugin could
     * do. Fresh drops are never touched.
     */
    public static final long DEFAULT_MIN_AGE_TICKS = 1200L;

    private final Set<String> removableTypes;
    private final long minAgeTicks;

    public SafetyPolicy(Set<String> removableTypes, long minAgeTicks) {
        this.removableTypes = Collections.unmodifiableSet(
                new HashSet<>(removableTypes));
        this.minAgeTicks = Math.max(0L, minAgeTicks);
    }

    public static SafetyPolicy defaults() {
        return new SafetyPolicy(DEFAULT_REMOVABLE, DEFAULT_MIN_AGE_TICKS);
    }

    public SafetyPolicy withMinAgeTicks(long ticks) {
        return new SafetyPolicy(removableTypes, ticks);
    }

    public SafetyPolicy withRemovableTypes(Set<String> types) {
        return new SafetyPolicy(types, minAgeTicks);
    }

    public Set<String> removableTypes() {
        return removableTypes;
    }

    public long minAgeTicks() {
        return minAgeTicks;
    }

    /** Whether this type could ever be auto-removed, ignoring the instance. */
    public boolean isRemovableType(String type) {
        return removableTypes.contains(type);
    }

    public Decision evaluate(EntityFacts entity) {
        if (!removableTypes.contains(entity.type)) {
            return Decision.blocked("type " + entity.type
                    + " is not on the removable allowlist");
        }
        if (entity.ageTicks < minAgeTicks) {
            return Decision.blocked("dropped less than "
                    + (minAgeTicks / 20) + "s ago");
        }
        if (entity.has(EntityFacts.NAMED)) {
            return Decision.blocked("has a custom name");
        }
        if (entity.has(EntityFacts.TAMED)) {
            return Decision.blocked("is a tamed pet");
        }
        if (entity.has(EntityFacts.LEASHED)) {
            return Decision.blocked("is leashed");
        }
        if (entity.has(EntityFacts.IN_VEHICLE)
                || entity.has(EntityFacts.HAS_PASSENGERS)) {
            return Decision.blocked("is riding or carrying something");
        }
        if (entity.has(EntityFacts.OWNED)) {
            return Decision.blocked("is reserved for a specific player");
        }
        if (entity.has(EntityFacts.PERSISTENT)) {
            // Deliberately made permanent - an item with unlimited lifetime, or
            // a mob set never to despawn. NOT the same as Bukkit's
            // Entity#isPersistent(), which merely means "gets saved to disk"
            // and is true for almost everything.
            return Decision.blocked("was deliberately made permanent");
        }
        if (entity.has(EntityFacts.HAS_EQUIPMENT)) {
            return Decision.blocked("carries equipment");
        }
        if (entity.has(EntityFacts.HAS_INVENTORY)) {
            return Decision.blocked("holds an inventory");
        }
        return Decision.ALLOWED;
    }

    /** Allowed, or blocked with a reason worth showing an admin. */
    public static final class Decision {

        public static final Decision ALLOWED = new Decision(true, null);

        public final boolean allowed;
        public final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        static Decision blocked(String reason) {
            return new Decision(false, reason);
        }

        /** For checks that live outside this class, such as land claims. */
        public static Decision blockedFor(String reason) {
            return new Decision(false, reason);
        }
    }
}
