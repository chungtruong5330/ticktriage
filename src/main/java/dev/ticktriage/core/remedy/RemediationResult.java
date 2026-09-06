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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.ticktriage.core.Stats;

/**
 * What a fix actually did, or would have done.
 *
 * <p>Skipped entities are reported by reason rather than as a single number.
 * "Skipped 340" tells an admin nothing; "skipped 340 because they were dropped
 * less than 60s ago" tells them the plugin is behaving, and is the difference
 * between trusting it and uninstalling it after the first scare.
 */
public final class RemediationResult {

    public final boolean dryRun;
    public final String operationId;
    public final int inspected;
    public final int affected;
    public final Map<String, Integer> skippedByReason;
    public final List<String> notes;

    private RemediationResult(boolean dryRun, String operationId, int inspected,
                              int affected, Map<String, Integer> skippedByReason,
                              List<String> notes) {
        this.dryRun = dryRun;
        this.operationId = operationId;
        this.inspected = inspected;
        this.affected = affected;
        this.skippedByReason = Collections.unmodifiableMap(
                new LinkedHashMap<>(skippedByReason));
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    public int totalSkipped() {
        int sum = 0;
        for (int v : skippedByReason.values()) {
            sum += v;
        }
        return sum;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "Dry run - nothing was changed." : "Fix applied.");
        if (!dryRun && operationId != null) {
            sb.append(" Operation ").append(operationId).append(".");
        }
        sb.append("\n  Inspected: ").append(Stats.formatCount(inspected));
        sb.append("\n  ").append(dryRun ? "Would remove: " : "Removed: ")
                .append(Stats.formatCount(affected));

        if (!skippedByReason.isEmpty()) {
            sb.append("\n  Skipped ").append(Stats.formatCount(totalSkipped()))
                    .append(":");
            for (Map.Entry<String, Integer> e : skippedByReason.entrySet()) {
                sb.append("\n    ").append(Stats.formatCount(e.getValue()))
                        .append(" - ").append(e.getKey());
            }
        }
        for (String note : notes) {
            sb.append("\n  ").append(note);
        }
        if (dryRun && affected > 0) {
            sb.append("\n  Run with 'confirm' to apply.");
        }
        return sb.toString();
    }

    /** Accumulates a result while an executor walks candidate entities. */
    public static final class Builder {

        private final boolean dryRun;
        private String operationId;
        private int inspected;
        private int affected;
        private final Map<String, Integer> skipped = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();

        public Builder(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public Builder operationId(String id) {
            this.operationId = id;
            return this;
        }

        public Builder inspected(int n) {
            this.inspected += n;
            return this;
        }

        public Builder affected(int n) {
            this.affected += n;
            return this;
        }

        public Builder skipped(String reason) {
            skipped.merge(reason, 1, Integer::sum);
            return this;
        }

        public Builder note(String note) {
            notes.add(note);
            return this;
        }

        public int affectedSoFar() {
            return affected;
        }

        public RemediationResult build() {
            return new RemediationResult(dryRun, operationId, inspected,
                    affected, skipped, notes);
        }
    }
}
