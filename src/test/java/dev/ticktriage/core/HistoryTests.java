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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the incident log, its on-disk encoding, and outbound alerts.
 *
 * <p>{@code java dev.ticktriage.core.HistoryTests}
 */
public final class HistoryTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final long T0 = 1_757_000_000_000L;

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static IncidentRecord record(long start, String rule,
                                         String headline, int removed) {
        return new IncidentRecord(start, 6, 5.5, 34, rule, "CRITICAL",
                headline, removed);
    }

    // --- encoding ---------------------------------------------------------

    static void testRecordRoundTrips() {
        IncidentRecord original = record(T0, "entity-flood",
                "4,207 dropped items in world 'world' around (412, 64, -1180)",
                4017);
        IncidentRecord back = IncidentRecord.decode(original.encode());
        check("a record survives encode then decode",
                back != null && back.startMillis == original.startMillis
                        && back.ruleId.equals(original.ruleId)
                        && back.headline.equals(original.headline)
                        && back.removed == original.removed
                        && Math.abs(back.worstTps - original.worstTps) < 0.01,
                back == null ? "decode returned null" : back.encode());
    }

    static void testHeadlineWithTabsAndNewlinesSurvives() {
        // World and entity names end up in headlines, and on a public server
        // those are close enough to untrusted input to matter.
        String nasty = "weird\tworld\nsecond line\\backslash";
        IncidentRecord back =
                IncidentRecord.decode(record(T0, "rule", nasty, 0).encode());
        check("tabs and newlines in a headline do not corrupt the row",
                back != null && back.headline.equals(nasty),
                back == null ? "null" : "got: " + back.headline);
    }

    static void testEncodedRecordIsOneLine() {
        String encoded = record(T0, "rule", "line one\nline two", 0).encode();
        check("an encoded record never spans two lines",
                !encoded.contains("\n"), encoded);
    }

    static void testMalformedLinesAreRejected() {
        check("a truncated row is rejected",
                IncidentRecord.decode("123\t6\t5.5") == null);
        check("a non-numeric row is rejected",
                IncidentRecord.decode("abc\tdef\tghi\tjkl\tm\tn\to\tp") == null);
        check("an empty line is rejected", IncidentRecord.decode("") == null);
        check("null is rejected", IncidentRecord.decode(null) == null);
    }

    // --- the log ----------------------------------------------------------

    static void testRepeatedAnalysisLogsOneIncident() {
        // The watcher re-analyses the same window every 30s; without the guard
        // one spike would be written dozens of times.
        IncidentLog log = new IncidentLog();
        check("the first sighting is recorded",
                log.record(record(T0, "entity-flood", "flood", 0)));
        check("the same incident is not recorded twice",
                !log.record(record(T0, "entity-flood", "flood", 0)));
        check("an older incident is not recorded either",
                !log.record(record(T0 - 5000, "entity-flood", "flood", 0)));
        check("a later incident is recorded",
                log.record(record(T0 + 5000, "entity-flood", "flood", 0)));
        check("only two rows exist", log.size() == 2,
                "size=" + log.size());
    }

    static void testIsNewMatchesRecord() {
        IncidentLog log = new IncidentLog();
        log.record(record(T0, "rule", "h", 0));
        List<Snapshot> samples = Arrays.asList(new Snapshot(T0, 200.0, 10, 1, 2,
                0, 1000L, new ArrayList<Snapshot.WorldStats>()));
        check("isNew agrees with record for an already-seen incident",
                !log.isNew(new Incident(samples)));
    }

    static void testCapacityDropsOldest() {
        IncidentLog log = new IncidentLog(3);
        for (int i = 0; i < 6; i++) {
            log.record(record(T0 + i * 1000L, "rule", "h" + i, 0));
        }
        List<IncidentRecord> all = log.all();
        check("the log is bounded", all.size() == 3, "size=" + all.size());
        check("the oldest rows are the ones dropped",
                all.get(0).headline.equals("h3")
                        && all.get(2).headline.equals("h5"),
                all.get(0).headline + ".." + all.get(2).headline);
    }

    static void testRecentIsNewestFirst() {
        IncidentLog log = new IncidentLog();
        for (int i = 0; i < 5; i++) {
            log.record(record(T0 + i * 1000L, "rule", "h" + i, 0));
        }
        List<IncidentRecord> recent = log.recent(2);
        check("recent() returns newest first and honours the limit",
                recent.size() == 2 && recent.get(0).headline.equals("h4")
                        && recent.get(1).headline.equals("h3"),
                recent.toString());
    }

    static void testRestoreAcceptsRowsInFileOrder() {
        // Loading from disk must not be blocked by the de-duplication guard.
        IncidentLog log = new IncidentLog();
        log.restore(Arrays.asList(
                record(T0, "rule", "old", 0),
                record(T0 + 1000, "rule", "newer", 0)));
        check("restore loads every row", log.size() == 2);
        check("restore still sets the high-water mark",
                !log.record(record(T0 + 500, "rule", "stale", 0)),
                "a row older than the newest loaded must still be rejected");
    }

    static void testAttachRemovalUpdatesTheLatestRow() {
        IncidentLog log = new IncidentLog();
        log.record(record(T0, "entity-flood", "flood", 0));
        check("a removal count can be attached after the fact",
                log.attachRemoval(T0, 4017)
                        && log.recent(1).get(0).removed == 4017);
        check("attaching to a different incident is refused",
                !log.attachRemoval(T0 + 9999, 5));
    }

    static void testSummaryCountsAndFindsCommonestCause() {
        IncidentLog log = new IncidentLog();
        log.record(record(T0, "entity-flood", "a", 100));
        log.record(record(T0 + 1000, "entity-flood", "b", 200));
        log.record(record(T0 + 2000, "chunk-load", "c", 0));
        IncidentLog.Summary summary = log.summarise(T0 - 1);
        check("the summary counts incidents", summary.incidents == 3,
                String.valueOf(summary.incidents));
        check("the summary totals removals", summary.entitiesRemoved == 300,
                String.valueOf(summary.entitiesRemoved));
        check("the summary names the commonest cause",
                "entity-flood".equals(summary.commonestRule)
                        && summary.commonestCount == 2,
                String.valueOf(summary.commonestRule));
        check("the summary renders readably",
                summary.render("24 hours").contains("3 incidents"),
                summary.render("24 hours"));
    }

    static void testSummaryWindowExcludesOlderRows() {
        IncidentLog log = new IncidentLog();
        log.record(record(T0, "entity-flood", "old", 0));
        log.record(record(T0 + 100_000, "entity-flood", "new", 0));
        check("rows before the window are excluded",
                log.summarise(T0 + 50_000).incidents == 1);
        check("an empty window says so",
                log.summarise(T0 + 500_000).render("24 hours")
                        .contains("No incidents"));
    }

    static void testOldRowsWithoutPeakTickTimeStillDecode() {
        // The peak-ms column was added after rows were already on disk. An
        // 8-column row must still load rather than being dropped as corrupt.
        String legacy = T0 + "	6	5.50	34	entity-flood	CRITICAL	flood	0";
        IncidentRecord back = IncidentRecord.decode(legacy);
        check("a row written before the peak-ms column still decodes",
                back != null && back.removed == 0 && back.peakMsPerTick == 0.0,
                back == null ? "null" : String.valueOf(back.peakMsPerTick));
        check("and a nine-column row round-trips",
                IncidentRecord.decode(new IncidentRecord(T0, 6, 5.5, 34, "r",
                        "CRITICAL", "h", 0, 181.0).encode()).peakMsPerTick
                        == 181.0);
    }

    static void testHistoryDoesNotClaimTpsFellWhenItDidNot() {
        // Found during a sweep: /tt history said "20.0 TPS for 12s" for an
        // incident where TPS never moved. Below a 50 ms tick the tick time is
        // the meaningful number.
        String quiet = new IncidentRecord(T0, 12, 20.0, 5, "entity-flood",
                "CRITICAL", "30,005 dropped items", 0, 39.0).summarise(T0);
        check("a sub-50ms incident reports tick time, not TPS",
                quiet.contains("39 ms ticks") && !quiet.contains("TPS"), quiet);
        String real = new IncidentRecord(T0, 6, 5.5, 5, "entity-flood",
                "CRITICAL", "flood", 0, 181.0).summarise(T0);
        check("a genuine TPS drop still reports TPS",
                real.contains("5.5 TPS"), real);
    }

    static void testSummariseRendersRelativeAge() {
        IncidentRecord r = record(T0, "entity-flood", "flood", 0);
        check("recent incidents read in minutes",
                r.summarise(T0 + 300_000L).startsWith("5m ago"),
                r.summarise(T0 + 300_000L));
        check("older incidents read in hours",
                r.summarise(T0 + 7_200_000L).startsWith("2h ago"),
                r.summarise(T0 + 7_200_000L));
        check("a fixed incident says what was removed",
                record(T0, "r", "h", 4017).summarise(T0).contains("4,017"),
                record(T0, "r", "h", 4017).summarise(T0));
    }

    // --- JSON -------------------------------------------------------------

    static void testJsonEscapesDangerousCharacters() {
        check("quotes and backslashes are escaped",
                Json.escape("say \"hi\" \\ here")
                        .equals("say \\\"hi\\\" \\\\ here"),
                Json.escape("say \"hi\" \\ here"));
        check("newlines and tabs are escaped",
                Json.escape("a\nb\tc").equals("a\\nb\\tc"),
                Json.escape("a\nb\tc"));
        check("control characters are escaped as unicode",
                Json.escape("").equals("\\u0001"),
                Json.escape(""));
        check("non-ascii is escaped as unicode",
                Json.escape("café").equals("caf\\u00e9"),
                Json.escape("café"));
    }

    static void testJsonObjectCannotBeBrokenOut() {
        // A headline containing a quote must not escape its JSON string.
        String json = Json.object("content", "world \"evil\":{} end");
        check("a crafted headline cannot break out of the JSON string",
                json.equals("{\"content\":\"world \\\"evil\\\":{} end\"}"),
                json);
    }

    static void testTruncateKeepsWithinLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("0123456789");
        }
        String truncated = Json.truncate(sb.toString(), 50);
        check("truncate respects the limit", truncated.length() <= 50,
                "length=" + truncated.length());
        check("truncate says it truncated",
                truncated.endsWith("(truncated)"), truncated);
        check("short text is left alone",
                Json.truncate("short", 50).equals("short"));
    }

    // --- webhook URL validation ------------------------------------------

    static void testValidWebhooksAreAccepted() {
        check("a normal Discord webhook is accepted",
                WebhookUrl.isDiscordWebhook(
                        "https://discord.com/api/webhooks/123/abcdef"));
        check("the legacy discordapp.com host is accepted",
                WebhookUrl.isDiscordWebhook(
                        "https://discordapp.com/api/webhooks/1/x"));
        check("subdomains of discord.com are accepted",
                WebhookUrl.isDiscordWebhook(
                        "https://ptb.discord.com/api/webhooks/1/x"));
    }

    static void testHostileUrlsAreRejected() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("plain http", "http://discord.com/api/webhooks/1/x");
        cases.put("a lookalike domain",
                "https://discord.com.evil.example/api/webhooks/1/x");
        cases.put("discord in the path only",
                "https://evil.example/discord.com/api/webhooks/1/x");
        cases.put("credentials in the authority",
                "https://discord.com@evil.example/api/webhooks/1/x");
        cases.put("the wrong path", "https://discord.com/api/oauth2/token");
        cases.put("an internal address", "https://127.0.0.1/api/webhooks/1/x");
        cases.put("a file url", "file:///etc/passwd");
        cases.put("nonsense", "not a url at all");

        List<String> accepted = new ArrayList<>();
        for (Map.Entry<String, String> e : cases.entrySet()) {
            if (WebhookUrl.isDiscordWebhook(e.getValue())) {
                accepted.add(e.getKey());
            }
        }
        check("hostile or malformed webhook URLs are all rejected",
                accepted.isEmpty(), "wrongly accepted: " + accepted);
        check("empty and null are rejected",
                !WebhookUrl.isDiscordWebhook("")
                        && !WebhookUrl.isDiscordWebhook(null));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\nIncident log and alert tests\n"
                + "----------------------------------------------------");
        List<java.lang.reflect.Method> tests = new ArrayList<>();
        for (java.lang.reflect.Method m
                : HistoryTests.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) {
                tests.add(m);
            }
        }
        tests.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (java.lang.reflect.Method m : tests) {
            try {
                m.setAccessible(true);
                m.invoke(null);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(m.getName());
                System.out.println("  ERROR " + m.getName() + ": " + cause);
            }
        }
        System.out.println("----------------------------------------------------");
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("failed: " + String.join(", ", failures));
            System.exit(1);
        }
    }
}
