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
package dev.ticktriage.core.remedy;

import java.util.ArrayList;
import java.util.List;

import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.DiagnosisTarget;
import dev.ticktriage.core.Report;
import dev.ticktriage.core.Stats;

/**
 * Turns diagnoses into a plan.
 *
 * <p>The planner's real job is deciding what <em>not</em> to automate. Every
 * refusal below returns advice with the reason attached, because "I could see
 * the problem but I am not going to touch it, and here is why" is more useful
 * to an admin than either silence or a surprise.
 *
 * <p>The one thing it will do unattended is clear the <em>excess</em> of a
 * removable entity type inside the chunk radius where the diagnosis found the
 * hotspot. Not everything of that type. Not server-wide. The excess, there.
 */
public final class RemediationPlanner {

    /** 2 chunks each way covers a 5x5 block of chunks around the hotspot -
     *  big enough for a farm's spill, small enough not to reach a base. */
    public static final int DEFAULT_RADIUS_CHUNKS = 2;

    /** Below this there is nothing worth acting on; leave it alone. */
    public static final int MIN_WORTH_CLEARING = 100;

    private final SafetyPolicy policy;
    private final int radiusChunks;

    public RemediationPlanner() {
        this(SafetyPolicy.defaults(), DEFAULT_RADIUS_CHUNKS);
    }

    public RemediationPlanner(SafetyPolicy policy, int radiusChunks) {
        this.policy = policy;
        this.radiusChunks = Math.max(0, radiusChunks);
    }

    public SafetyPolicy policy() {
        return policy;
    }

    public RemediationPlan planFor(Report report) {
        if (report == null || report.healthy) {
            return RemediationPlan.empty();
        }
        List<RemediationAction> actions = new ArrayList<>();
        for (Diagnosis d : report.diagnoses) {
            RemediationAction action = planFor(d);
            if (action != null) {
                actions.add(action);
            }
        }
        return new RemediationPlan(actions);
    }

    /** @return the action for one diagnosis, or null if it warrants nothing. */
    public RemediationAction planFor(Diagnosis diagnosis) {
        if (diagnosis == null) {
            return null;
        }

        // INFO means "nothing is broken". Acting on it would be acting on a
        // healthy server.
        if (diagnosis.severity == Diagnosis.Severity.INFO) {
            return null;
        }

        DiagnosisTarget target = diagnosis.target;
        if (target == null) {
            return RemediationAction.advise(diagnosis.headline,
                    diagnosis.suggestedFix);
        }

        if (target.blockEntity) {
            return RemediationAction.advise(diagnosis.headline,
                    "Not automated: fixing this means breaking blocks somebody"
                            + " placed. " + diagnosis.suggestedFix);
        }

        if (!policy.isRemovableType(target.type)) {
            return RemediationAction.advise(diagnosis.headline,
                    "Not automated: " + target.type + " is not on the removable"
                            + " allowlist, because clearing it risks destroying"
                            + " something a player owns. "
                            + diagnosis.suggestedFix);
        }

        if (!target.hasHotspot) {
            return RemediationAction.advise(diagnosis.headline,
                    "Not automated: these are spread across the world with no"
                            + " single hotspot, so any clear would be"
                            + " indiscriminate. " + diagnosis.suggestedFix);
        }

        int excess = target.excess();
        if (excess < MIN_WORTH_CLEARING) {
            return RemediationAction.advise(diagnosis.headline,
                    "Not automated: only " + Stats.formatCount(excess)
                            + " above normal, which is not worth touching the"
                            + " world for. " + diagnosis.suggestedFix);
        }

        String rationale = "Clears the " + Stats.formatCount(excess)
                + " above this server's normal "
                + Stats.formatCount(target.baseline) + ", inside "
                + radiusChunks + " chunks of the hotspot only. Entities newer"
                + " than " + (policy.minAgeTicks() / 20) + "s, named, tamed,"
                + " equipped or carrying an inventory are skipped.";

        return RemediationAction.clearEntities(target.world, target.type,
                target.chunkX, target.chunkZ, radiusChunks, excess, rationale);
    }
}
