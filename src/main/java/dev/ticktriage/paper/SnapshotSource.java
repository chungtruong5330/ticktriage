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

import java.util.function.Consumer;

import dev.ticktriage.core.Snapshot;

/**
 * Produces one {@link Snapshot} of the server.
 *
 * <p>The callback is what makes this work on both platforms. On Paper the
 * snapshot is ready immediately and the callback fires inline; on Folia the
 * census is spread across region threads and the callback fires later, on the
 * global thread, once every region has reported.
 */
public interface SnapshotSource {

    /** Short description of the sampling strategy, for the status command. */
    String describe();

    /**
     * Starts a sample. May invoke {@code onComplete} synchronously or later,
     * but always on a thread where it is safe to touch plugin state.
     * May decline to start (and never call back) if a previous round is still
     * outstanding.
     */
    void sample(Consumer<Snapshot> onComplete);
}
