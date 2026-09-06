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
package dev.ticktriage.core;

/**
 * One hypothesis about why an incident happened.
 *
 * <p>Rules are independent and may all fire at once - a server can be short of
 * memory <em>and</em> drowning in dropped items. The engine ranks them rather
 * than picking a single winner, because telling an owner only about the biggest
 * problem tends to produce a second support ticket an hour later.
 */
public interface DiagnosisRule {

    /** Stable identifier, used in configs and for suppressing noisy rules. */
    String id();

    /**
     * @return a diagnosis, or null when this rule has nothing to say. Returning
     *         null is the normal case and must be cheap.
     */
    Diagnosis evaluate(Incident incident, Baseline baseline);

    /**
     * A verdict on the server's steady state, with no incident involved.
     *
     * <p>Most rules have nothing to say here and the default is correct for
     * them: an entity flood is only interesting relative to a spike. But a
     * server that is <em>uniformly</em> slow never produces a spike to hang a
     * diagnosis on - its baseline is the problem - and a detector that only
     * looks for spikes can never see it. Rules that describe a standing
     * condition override this.
     *
     * @return a diagnosis, or null when this rule only speaks about incidents
     */
    default Diagnosis evaluateBaseline(Baseline baseline) {
        return null;
    }
}
