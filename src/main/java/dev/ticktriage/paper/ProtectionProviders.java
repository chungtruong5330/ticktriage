package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.ticktriage.core.remedy.CompositeProtection;
import dev.ticktriage.core.remedy.ProtectionOracle;

/**
 * Wires up whichever land-claim plugins this server actually runs.
 *
 * <p>Absent is fine - not every server has WorldGuard, and there is nothing to
 * consult. <b>Present but broken is not fine.</b> If an integration fails to
 * initialise, this installs {@link ProtectionOracle#ALL}, which makes every
 * location protected and stops remediation entirely.
 *
 * <p>That is deliberate. The alternative - carrying on with claim checking
 * silently disabled - means the first sign of trouble is a player asking where
 * their base went. A plugin that loudly does nothing is recoverable; one that
 * quietly deletes things is not.
 */
public final class ProtectionProviders {

    private ProtectionProviders() {
    }

    public static ProtectionOracle build(Logger log,
                                         ProtectionOracle configured,
                                         boolean useWorldGuard,
                                         boolean useGriefPrevention) {
        List<ProtectionOracle> oracles = new ArrayList<>();
        if (configured != null && configured != ProtectionOracle.NONE) {
            oracles.add(configured);
        }

        if (useWorldGuard) {
            add(log, oracles, "WorldGuard", new Supplier() {
                @Override
                public ProtectionOracle get() {
                    return WorldGuardProtection.tryCreate();
                }
            });
        }
        if (useGriefPrevention) {
            add(log, oracles, "GriefPrevention", new Supplier() {
                @Override
                public ProtectionOracle get() {
                    return GriefPreventionProtection.tryCreate();
                }
            });
        }

        ProtectionOracle result = CompositeProtection.of(oracles);
        log.info("Claim protection: " + result.describe());
        return result;
    }

    private static void add(Logger log, List<ProtectionOracle> oracles,
                            String name, Supplier supplier) {
        try {
            ProtectionOracle oracle = supplier.get();
            if (oracle == null) {
                // Not installed. Nothing to integrate with, nothing to say.
                return;
            }
            oracles.add(oracle);
            log.info("Hooked into " + name + " for claim protection.");
        } catch (Throwable t) {
            log.log(Level.SEVERE, name + " is installed but TickTriage could"
                    + " not hook into it. Refusing to remove anything until"
                    + " this is fixed.", t);
            oracles.add(ProtectionOracle.ALL);
        }
    }

    /** Local functional type so this compiles without pulling in java.util
     *  function imports for one use. */
    private interface Supplier {
        ProtectionOracle get();
    }
}
