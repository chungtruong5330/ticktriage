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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded log of past incidents, with de-duplication.
 *
 * <p>The de-duplication matters more than it looks. The watcher re-analyses the
 * same rolling window every thirty seconds, so without it a single lag spike
 * would be written to disk dozens of times and the history would be useless.
 * An incident is only new if it started later than the last one recorded.
 */
public final class IncidentLog {

    public static final int DEFAULT_CAPACITY = 500;

    private final Deque<IncidentRecord> records = new ArrayDeque<>();
    private final int capacity;
    private long lastStartMillis = Long.MIN_VALUE;

    public IncidentLog() {
        this(DEFAULT_CAPACITY);
    }

    public IncidentLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Whether this incident has already been logged. */
    public synchronized boolean isNew(Incident incident) {
        return incident != null && incident.startMillis > lastStartMillis;
    }

    /** @return true if it was recorded, false if it was a repeat. */
    public synchronized boolean record(IncidentRecord record) {
        if (record == null || record.startMillis <= lastStartMillis) {
            return false;
        }
        records.addLast(record);
        lastStartMillis = record.startMillis;
        while (records.size() > capacity) {
            records.removeFirst();
        }
        return true;
    }

    /** Load from disk without the de-duplication guard rejecting older rows. */
    public synchronized void restore(List<IncidentRecord> loaded) {
        for (IncidentRecord r : loaded) {
            if (r == null) {
                continue;
            }
            records.addLast(r);
            lastStartMillis = Math.max(lastStartMillis, r.startMillis);
        }
        while (records.size() > capacity) {
            records.removeFirst();
        }
    }

    /** Attach a removal count to the most recent record, after a fix runs. */
    public synchronized boolean attachRemoval(long startMillis, int removed) {
        if (records.isEmpty() || removed <= 0) {
            return false;
        }
        IncidentRecord last = records.peekLast();
        if (last.startMillis != startMillis) {
            return false;
        }
        records.removeLast();
        records.addLast(last.withRemoved(last.removed + removed));
        return true;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized List<IncidentRecord> all() {
        return new ArrayList<>(records);
    }

    /** Newest first. */
    public synchronized List<IncidentRecord> recent(int limit) {
        List<IncidentRecord> out = new ArrayList<>();
        IncidentRecord[] array = records.toArray(new IncidentRecord[0]);
        for (int i = array.length - 1; i >= 0 && out.size() < limit; i--) {
            out.add(array[i]);
        }
        return out;
    }

    public synchronized Summary summarise(long sinceMillis) {
        int count = 0;
        int removed = 0;
        double worstTps = 20.0;
        Map<String, Integer> byRule = new LinkedHashMap<>();
        for (IncidentRecord r : records) {
            if (r.startMillis < sinceMillis) {
                continue;
            }
            count++;
            removed += r.removed;
            worstTps = Math.min(worstTps, r.worstTps);
            byRule.merge(r.ruleId, 1, Integer::sum);
        }
        String commonest = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : byRule.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                commonest = e.getKey();
            }
        }
        return new Summary(count, removed, count == 0 ? 20.0 : worstTps,
                commonest, best);
    }

    /** Aggregate view over a time window, for {@code /ticktriage history}. */
    public static final class Summary {

        public final int incidents;
        public final int entitiesRemoved;
        public final double worstTps;
        public final String commonestRule;
        public final int commonestCount;

        Summary(int incidents, int entitiesRemoved, double worstTps,
                String commonestRule, int commonestCount) {
            this.incidents = incidents;
            this.entitiesRemoved = entitiesRemoved;
            this.worstTps = worstTps;
            this.commonestRule = commonestRule;
            this.commonestCount = commonestCount;
        }

        public String render(String windowLabel) {
            if (incidents == 0) {
                return "No incidents in the last " + windowLabel + ".";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(incidents).append(" incident")
                    .append(incidents == 1 ? "" : "s").append(" in the last ")
                    .append(windowLabel).append(", worst ")
                    .append(Stats.formatDouble(worstTps, 1)).append(" TPS");
            if (commonestRule != null && incidents > 1) {
                sb.append(". Most common cause: ").append(commonestRule)
                        .append(" (").append(commonestCount).append(")");
            }
            if (entitiesRemoved > 0) {
                sb.append(". ").append(Stats.formatCount(entitiesRemoved))
                        .append(" entities removed by fixes");
            }
            return sb.append(".").toString();
        }
    }
}
