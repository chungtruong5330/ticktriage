package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the diagnosis engine, driven by synthetic server histories.
 *
 * <p>No Paper, no server, no network: {@code java dev.ticktriage.core.CoreTests}.
 *
 * <p>Roughly half of these assert that a rule does <em>not</em> fire. A lag
 * doctor that cries wolf gets uninstalled, so the false-positive cases matter
 * at least as much as the detections.
 */
public final class CoreTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final long T0 = 1_757_000_000_000L;
    private static final long HEAP_MAX = 8L * 1024 * 1024 * 1024;
    private static final String WORLD = "world";

    // --- helpers ----------------------------------------------------------

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

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    /** One sample of a single-world server, with the knobs the tests vary. */
    private static Snapshot snap(long t, double ms, int players, int items,
                                 int hoppers, int chunks, double heapFraction,
                                 long gcMillis) {
        Snapshot.WorldStats world = new Snapshot.WorldStats(
                WORLD, chunks,
                map("ITEM", items, "ZOMBIE", 40, "VILLAGER", 12),
                map("HOPPER", hoppers, "CHEST", 150),
                Arrays.asList(
                        new Snapshot.EntityCluster("ITEM",
                                Math.max(1, (int) (items * 0.8)), 412, 64, -1180),
                        new Snapshot.EntityCluster("HOPPER",
                                Math.max(1, (int) (hoppers * 0.7)), -220, 12, 640)));
        return new Snapshot(t, ms, players,
                (long) (heapFraction * HEAP_MAX), HEAP_MAX, gcMillis, 1000L,
                Arrays.asList(world));
    }

    /** A calm server: 40 ms ticks, steady counts. */
    private static History healthy(int samples) {
        History h = new History();
        for (int i = 0; i < samples; i++) {
            h.add(snap(T0 + i * 1000L, 40.0, 20, 180, 120, 800, 0.55, 5));
        }
        return h;
    }

    private static Diagnosis find(List<Diagnosis> list, String ruleId) {
        for (Diagnosis d : list) {
            if (d.ruleId.equals(ruleId)) {
                return d;
            }
        }
        return null;
    }

    private static Report analyse(History h) {
        return new DiagnosisEngine().analyse(h);
    }

    // --- baseline & detection --------------------------------------------

    static void testHealthyServerReportsHealthy() {
        Report r = analyse(healthy(120));
        check("a calm server produces a healthy report", r.healthy,
                r.healthy ? "" : r.render());
    }

    static void testTooLittleHistoryIsNotDiagnosed() {
        History h = new History();
        for (int i = 0; i < 10; i++) {
            h.add(snap(T0 + i * 1000L, 200.0, 20, 9999, 120, 800, 0.99, 800));
        }
        check("a server with 10 samples is not diagnosed", analyse(h).healthy,
                "must not guess before it has a baseline");
    }

    static void testMedianBaselineIgnoresTheIncident() {
        History h = healthy(120);
        for (int i = 0; i < 8; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 300.0, 20, 9000, 120, 800, 0.55, 5));
        }
        Baseline b = Baseline.from(h.samples());
        check("median baseline is not dragged by a spike",
                Math.abs(b.msPerTick - 40.0) < 0.001,
                "baseline msPerTick=" + b.msPerTick);
    }

    static void testShortBlipIsNotAnIncident() {
        History h = healthy(120);
        // Two bad samples, below the three-sample minimum.
        h.add(snap(T0 + 120_000L, 200.0, 20, 180, 120, 800, 0.55, 5));
        h.add(snap(T0 + 121_000L, 200.0, 20, 180, 120, 800, 0.55, 5));
        for (int i = 0; i < 10; i++) {
            h.add(snap(T0 + (122 + i) * 1000L, 40.0, 20, 180, 120, 800, 0.55, 5));
        }
        check("a two-sample blip is not reported as an incident",
                analyse(h).healthy);
    }

    static void testBriefRecoveryDoesNotSplitAnIncident() {
        List<Snapshot> samples = new ArrayList<>(healthy(60).samples());
        double[] pattern = {200, 200, 40, 200, 200, 200};
        for (int i = 0; i < pattern.length; i++) {
            samples.add(snap(T0 + (60 + i) * 1000L, pattern[i], 20, 180, 120,
                    800, 0.55, 5));
        }
        Baseline b = Baseline.from(samples);
        List<Incident> incidents = new IncidentDetector().detect(samples, b);
        check("a one-sample recovery does not split one incident into two",
                incidents.size() == 1,
                "found " + incidents.size() + " incidents");
    }

    static void testSlowButStableServerIsNotFlagged() {
        // Consistently 52 ms ticks. Slow, but nothing is spiking.
        History h = new History();
        for (int i = 0; i < 120; i++) {
            h.add(snap(T0 + i * 1000L, 52.0, 20, 180, 120, 800, 0.55, 5));
        }
        Report r = analyse(h);
        check("a consistently slow server raises no incident", r.healthy,
                "relative threshold must stop constant alerts");
    }

    // --- entity flood -----------------------------------------------------

    static History itemFlood() {
        History h = healthy(120);
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 180.0, 20, 4207, 120, 800, 0.55, 5));
        }
        return h;
    }

    static void testItemFloodIsDetected() {
        Report r = analyse(itemFlood());
        Diagnosis d = find(r.diagnoses, "entity-flood");
        check("an item flood is diagnosed", d != null,
                d == null ? r.render() : "");
        if (d != null) {
            check("the headline names dropped items and the count",
                    d.headline.contains("dropped items")
                            && d.headline.contains("4,207"), d.headline);
            check("the headline pinpoints the hotspot",
                    d.headline.contains("(412, 64, -1180)"), d.headline);
            check("the fix is specific, not generic advice",
                    d.suggestedFix.contains("merge-radius"), d.suggestedFix);
            check("an item flood ranks as the primary diagnosis",
                    "entity-flood".equals(r.primary().ruleId),
                    r.primary().ruleId);
        }
    }

    static void testBigServerWithSteadyCountsIsNotFlagged() {
        // 5,000 items at all times is this server's normal.
        History h = new History();
        for (int i = 0; i < 120; i++) {
            h.add(snap(T0 + i * 1000L, 40.0, 20, 5000, 120, 800, 0.55, 5));
        }
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 180.0, 20, 5100, 120, 800, 0.55, 5));
        }
        check("a consistently large server is not accused of an item flood",
                find(analyse(h).diagnoses, "entity-flood") == null,
                "thresholds must be relative to this server's own normal");
    }

    static void testConfidenceScalesWithSeverity() {
        History mild = healthy(120);
        for (int i = 0; i < 6; i++) {
            mild.add(snap(T0 + (120 + i) * 1000L, 180.0, 20, 700, 120, 800, 0.55, 5));
        }
        Diagnosis small = find(analyse(mild).diagnoses, "entity-flood");
        Diagnosis large = find(analyse(itemFlood()).diagnoses, "entity-flood");
        check("a bigger flood yields higher confidence",
                small != null && large != null
                        && large.confidence > small.confidence,
                small == null || large == null ? "a rule did not fire"
                        : small.confidence + " vs " + large.confidence);
    }

    // --- other rules ------------------------------------------------------

    static void testHopperFloodIsDetected() {
        History h = healthy(120);
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 150.0, 20, 180, 1800, 800, 0.55, 5));
        }
        Diagnosis d = find(analyse(h).diagnoses, "block-entity-flood");
        check("a hopper flood is diagnosed", d != null);
        if (d != null) {
            check("hopper advice names the actual config key",
                    d.suggestedFix.contains("ticks-per.hopper-transfer"),
                    d.suggestedFix);
        }
    }

    static void testChunkSpikeIsDetected() {
        History h = healthy(120);
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 150.0, 20, 180, 120, 4200, 0.55, 5));
        }
        Diagnosis d = find(analyse(h).diagnoses, "chunk-load");
        check("a chunk-loading spike is diagnosed", d != null);
        if (d != null) {
            check("chunk advice suggests pre-generation",
                    d.suggestedFix.contains("Chunky"), d.suggestedFix);
        }
    }

    static void testMemoryPressureNeedsBothSignals() {
        History thrash = healthy(120);
        for (int i = 0; i < 6; i++) {
            thrash.add(snap(T0 + (120 + i) * 1000L, 190.0, 20, 180, 120, 800,
                    0.97, 450));
        }
        check("a full heap with long GC pauses is diagnosed",
                find(analyse(thrash).diagnoses, "memory-pressure") != null);

        History fullButFine = healthy(120);
        for (int i = 0; i < 6; i++) {
            fullButFine.add(snap(T0 + (120 + i) * 1000L, 190.0, 20, 180, 120,
                    800, 0.97, 8));
        }
        check("a full heap with no GC pauses is NOT called a memory problem",
                find(analyse(fullButFine).diagnoses, "memory-pressure") == null,
                "high heap usage alone is normal JVM behaviour");
    }

    static void testChronicOverloadIsReported() {
        History h = new History();
        for (int i = 0; i < 120; i++) {
            h.add(snap(T0 + i * 1000L, 48.0, 20, 180, 120, 800, 0.55, 5));
        }
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 190.0, 20, 4207, 120, 800, 0.55, 5));
        }
        Diagnosis d = find(analyse(h).diagnoses, "chronic-overload");
        check("a permanently overloaded server is told so", d != null);
    }

    static void testPlayerSurgeIsInfoAndRanksBelowFaults() {
        History h = healthy(120);
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 190.0, 65, 4207, 120, 800, 0.55, 5));
        }
        Report r = analyse(h);
        Diagnosis surge = find(r.diagnoses, "player-surge");
        check("a player surge is reported", surge != null);
        if (surge != null) {
            check("a player surge is INFO, not a fault",
                    surge.severity == Diagnosis.Severity.INFO,
                    String.valueOf(surge.severity));
            check("a real fault outranks the player surge",
                    !"player-surge".equals(r.primary().ruleId),
                    "primary was " + r.primary().ruleId);
        }
    }

    static void testUnexplainedSpikeIsAdmitted() {
        // A spike with every tracked metric held completely flat.
        History h = healthy(120);
        for (int i = 0; i < 6; i++) {
            h.add(snap(T0 + (120 + i) * 1000L, 250.0, 20, 180, 120, 800, 0.55, 5));
        }
        Report r = analyse(h);
        Diagnosis d = find(r.diagnoses, "unexplained");
        check("an inexplicable spike is admitted rather than guessed at",
                d != null, r.render());
        if (d != null) {
            check("the unexplained fallback still suggests a next step",
                    d.suggestedFix.contains("spark"), d.suggestedFix);
            check("the unexplained fallback claims low confidence",
                    d.confidence <= 0.3, String.valueOf(d.confidence));
        }
    }

    // --- rendering --------------------------------------------------------

    static void testReportRenders() {
        String text = analyse(itemFlood()).render();
        check("the report renders the incident and a fix",
                text.contains("TPS fell to") && text.contains("Fix:"), text);
    }

    static void testSeverityOrderingHoldsAcrossRules() {
        Diagnosis info = new Diagnosis("a", 0.99, Diagnosis.Severity.INFO,
                "h", "f");
        Diagnosis critical = new Diagnosis("b", 0.40,
                Diagnosis.Severity.CRITICAL, "h", "f");
        List<Diagnosis> sorted = new DiagnosisEngine(
                Arrays.<DiagnosisRule>asList(fixed(info), fixed(critical)),
                new IncidentDetector())
                .diagnose(new Incident(Arrays.asList(
                        snap(T0, 200.0, 20, 180, 120, 800, 0.55, 5))),
                        Baseline.from(healthy(60).samples()));
        check("a low-confidence CRITICAL outranks a high-confidence INFO",
                "b".equals(sorted.get(0).ruleId), sorted.toString());
    }

    private static DiagnosisRule fixed(final Diagnosis d) {
        return new DiagnosisRule() {
            @Override
            public String id() {
                return d.ruleId;
            }

            @Override
            public Diagnosis evaluate(Incident incident, Baseline baseline) {
                return d;
            }
        };
    }

    // --- runner -----------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("\nTickTriage core tests\n"
                + "----------------------------------------------------");
        List<java.lang.reflect.Method> tests = new ArrayList<>();
        for (java.lang.reflect.Method m : CoreTests.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test")
                    && m.getParameterCount() == 0) {
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
