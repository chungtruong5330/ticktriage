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
package dev.ticktriage.core;

/**
 * Decides which samples do the expensive block-entity census.
 *
 * <p>Trivial logic, extracted into {@code core} because the obvious version was
 * wrong for two years of wall-clock time and nobody could have noticed. It was:
 *
 * <pre>
 *   private int samplesSinceBlockCensus = Integer.MAX_VALUE;  // census first time
 *   boolean due = ++samplesSinceBlockCensus &gt;= every;
 * </pre>
 *
 * <p>{@code Integer.MAX_VALUE + 1} overflows to {@code Integer.MIN_VALUE}, so
 * the first check compared minus two billion against the interval, failed, and
 * then counted upward one sample at a time. The census would first have run
 * after roughly 4.3 billion samples - about 270 years at one every two seconds.
 * Hoppers and spawners were therefore never counted on any server, and the
 * failure was completely silent: a census that never runs looks exactly like a
 * server with no hoppers.
 *
 * <p>Counting down from the interval cannot overflow and needs no sentinel.
 */
public final class CensusSchedule {

    private final int every;
    private int remaining;

    public CensusSchedule(int every) {
        this.every = Math.max(1, every);
        this.remaining = 0;
    }

    public int interval() {
        return every;
    }

    /** True on the first call, then once every {@code every} calls. */
    public boolean due() {
        if (remaining <= 0) {
            remaining = every - 1;
            return true;
        }
        remaining--;
        return false;
    }
}
