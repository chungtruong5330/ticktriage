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
 * A past incident, boiled down to one line for the log on disk.
 *
 * <p>Encoded as tab-separated values rather than JSON so the core package keeps
 * its zero dependencies, and so an admin can open the file and read it. Any
 * tabs or newlines in the headline are escaped, because a diagnosis headline is
 * built from world and entity names and those are attacker-adjacent input on a
 * public server.
 */
public final class IncidentRecord {

    public final long startMillis;
    public final int durationSeconds;
    public final double worstTps;
    public final int playerCount;
    public final String ruleId;
    public final String severity;
    public final String headline;
    /** Entities removed by a fix for this incident, or 0 if none was applied. */
    public final int removed;

    public IncidentRecord(long startMillis, int durationSeconds, double worstTps,
                          int playerCount, String ruleId, String severity,
                          String headline, int removed) {
        this.startMillis = startMillis;
        this.durationSeconds = durationSeconds;
        this.worstTps = worstTps;
        this.playerCount = playerCount;
        this.ruleId = ruleId == null ? "unknown" : ruleId;
        this.severity = severity == null ? "WARNING" : severity;
        this.headline = headline == null ? "" : headline;
        this.removed = removed;
    }

    public static IncidentRecord from(Incident incident, Diagnosis primary,
                                      int removed) {
        return new IncidentRecord(incident.startMillis,
                (int) Math.round(incident.durationSeconds()),
                incident.worstTps(), incident.peak.playerCount,
                primary == null ? "unknown" : primary.ruleId,
                primary == null ? "WARNING" : primary.severity.name(),
                primary == null ? "Unexplained incident" : primary.headline,
                removed);
    }

    public IncidentRecord withRemoved(int removed) {
        return new IncidentRecord(startMillis, durationSeconds, worstTps,
                playerCount, ruleId, severity, headline, removed);
    }

    public String encode() {
        return startMillis + "\t" + durationSeconds + "\t"
                + Stats.formatDouble(worstTps, 2) + "\t" + playerCount + "\t"
                + escape(ruleId) + "\t" + escape(severity) + "\t"
                + escape(headline) + "\t" + removed;
    }

    /** @return the record, or null if the line is malformed. */
    public static IncidentRecord decode(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 8) {
            return null;
        }
        try {
            return new IncidentRecord(Long.parseLong(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    unescape(parts[4]), unescape(parts[5]), unescape(parts[6]),
                    Integer.parseInt(parts[7].trim()));
        } catch (RuntimeException e) {
            // A corrupt line loses one incident, not the whole file.
            return null;
        }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "");
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 't') {
                    sb.append('\t');
                } else if (next == 'n') {
                    sb.append('\n');
                } else if (next == '\\') {
                    sb.append('\\');
                } else {
                    sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** One-line summary for {@code /ticktriage history}. */
    public String summarise(long nowMillis) {
        long agoSeconds = Math.max(0, (nowMillis - startMillis) / 1000L);
        String ago = agoSeconds < 3600
                ? (agoSeconds / 60) + "m ago"
                : (agoSeconds < 86400
                        ? (agoSeconds / 3600) + "h ago"
                        : (agoSeconds / 86400) + "d ago");
        StringBuilder sb = new StringBuilder();
        sb.append(ago).append(" - ").append(Stats.formatDouble(worstTps, 1))
                .append(" TPS for ").append(durationSeconds).append("s - ")
                .append(headline);
        if (removed > 0) {
            sb.append(" (fixed: ").append(Stats.formatCount(removed))
                    .append(" removed)");
        }
        return sb.toString();
    }
}
