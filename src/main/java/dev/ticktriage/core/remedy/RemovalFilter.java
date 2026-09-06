package dev.ticktriage.core.remedy;

/**
 * The single gate every candidate entity passes through before removal.
 *
 * <p>Combining the two checks here rather than in the executor means the whole
 * decision - entity properties <em>and</em> land claims - is one testable unit
 * with no Bukkit anywhere near it. The executor's only job becomes supplying
 * facts and obeying the answer.
 */
public final class RemovalFilter {

    private final SafetyPolicy policy;
    private final ProtectionOracle protection;

    public RemovalFilter(SafetyPolicy policy, ProtectionOracle protection) {
        this.policy = policy;
        this.protection = protection == null ? ProtectionOracle.NONE : protection;
    }

    public SafetyPolicy policy() {
        return policy;
    }

    public ProtectionOracle protection() {
        return protection;
    }

    public SafetyPolicy.Decision evaluate(String world, EntityFacts entity) {
        SafetyPolicy.Decision decision = policy.evaluate(entity);
        if (!decision.allowed) {
            return decision;
        }
        // Claim lookups can be expensive, so they run last - only for entities
        // that already passed every cheap check.
        boolean claimed;
        try {
            claimed = protection.isProtected(world, entity.x, entity.y, entity.z);
        } catch (Throwable t) {
            claimed = true;
        }
        if (claimed) {
            return SafetyPolicy.Decision.blockedFor(
                    "inside a protected region or claim");
        }
        return decision;
    }
}
