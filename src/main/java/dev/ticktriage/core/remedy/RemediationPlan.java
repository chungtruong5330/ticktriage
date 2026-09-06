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
package dev.ticktriage.core.remedy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What TickTriage proposes to do about an incident. */
public final class RemediationPlan {

    public final List<RemediationAction> actions;

    public RemediationPlan(List<RemediationAction> actions) {
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    }

    public static RemediationPlan empty() {
        return new RemediationPlan(new ArrayList<RemediationAction>());
    }

    public List<RemediationAction> executable() {
        List<RemediationAction> out = new ArrayList<>();
        for (RemediationAction a : actions) {
            if (a.isExecutable()) {
                out.add(a);
            }
        }
        return out;
    }

    public List<RemediationAction> advice() {
        List<RemediationAction> out = new ArrayList<>();
        for (RemediationAction a : actions) {
            if (!a.isExecutable()) {
                out.add(a);
            }
        }
        return out;
    }

    public boolean hasExecutableActions() {
        return !executable().isEmpty();
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    /** Total entities this plan could remove at most. */
    public int maxRemovals() {
        int sum = 0;
        for (RemediationAction a : executable()) {
            sum += a.maxToRemove;
        }
        return sum;
    }

    public String render() {
        if (actions.isEmpty()) {
            return "Nothing to do - no incident with an actionable cause.";
        }

        StringBuilder sb = new StringBuilder();
        List<RemediationAction> auto = executable();
        List<RemediationAction> advice = advice();

        if (auto.isEmpty()) {
            sb.append("No automatic fix available. TickTriage will not act on"
                    + " this kind of problem without a human.");
        } else {
            sb.append("Automatic fixes (").append(auto.size()).append("):");
            for (RemediationAction a : auto) {
                sb.append("\n  - ").append(a.description);
                sb.append("\n    why: ").append(a.rationale);
            }
        }

        if (!advice.isEmpty()) {
            sb.append("\n\nNeeds a human (").append(advice.size()).append("):");
            for (RemediationAction a : advice) {
                sb.append("\n  - ").append(a.description);
                sb.append("\n    ").append(a.rationale);
            }
        }
        return sb.toString();
    }
}
