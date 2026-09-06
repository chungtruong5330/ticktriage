package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.ticktriage.core.rules.BlockEntityFloodRule;
import dev.ticktriage.core.rules.ChronicOverloadRule;
import dev.ticktriage.core.rules.ChunkLoadRule;
import dev.ticktriage.core.rules.EntityFloodRule;
import dev.ticktriage.core.rules.MemoryPressureRule;
import dev.ticktriage.core.rules.PlayerSurgeRule;

/**
 * Runs every rule against an incident and ranks what comes back.
 *
 * <p>Ranking is by severity first and confidence second, so an INFO-level
 * "you just got busy" can never bury a CRITICAL "your heap is thrashing", no
 * matter how confident the former is.
 */
public final class DiagnosisEngine {

    /** Below this, the baseline is too thin for any comparison to mean much. */
    public static final int MIN_SAMPLES_TO_ANALYSE = 30;

    private final List<DiagnosisRule> rules;
    private final IncidentDetector detector;

    public DiagnosisEngine() {
        this(defaultRules(), new IncidentDetector());
    }

    public DiagnosisEngine(List<DiagnosisRule> rules, IncidentDetector detector) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.detector = detector;
    }

    public static List<DiagnosisRule> defaultRules() {
        return Arrays.<DiagnosisRule>asList(
                new EntityFloodRule(),
                new BlockEntityFloodRule(),
                new ChunkLoadRule(),
                new MemoryPressureRule(),
                new ChronicOverloadRule(),
                new PlayerSurgeRule());
    }

    public List<DiagnosisRule> rules() {
        return rules;
    }

    public Report analyse(History history) {
        List<Snapshot> samples = history.samples();
        Baseline provisional = Baseline.from(samples);

        if (samples.size() < MIN_SAMPLES_TO_ANALYSE) {
            return Report.healthy(provisional);
        }

        Incident incident = detector.mostRecent(samples, provisional);
        if (incident == null) {
            return Report.healthy(provisional);
        }

        // Recompute the baseline with the incident removed. Medians are already
        // robust, but a long incident in a short window would still drag them.
        Baseline clean = Baseline.from(history.samplesExcluding(incident));
        Baseline baseline = clean.isUsable() ? clean : provisional;

        return Report.of(incident, baseline, diagnose(incident, baseline));
    }

    public List<Diagnosis> diagnose(Incident incident, Baseline baseline) {
        List<Diagnosis> found = new ArrayList<>();
        for (DiagnosisRule rule : rules) {
            Diagnosis d = rule.evaluate(incident, baseline);
            if (d != null) {
                found.add(d);
            }
        }

        found.sort(Comparator
                .comparingInt((Diagnosis d) -> severityRank(d.severity))
                .thenComparingDouble(d -> d.confidence)
                .reversed());

        if (found.isEmpty()) {
            found.add(unexplained(incident, baseline));
        }
        return found;
    }

    private static int severityRank(Diagnosis.Severity severity) {
        switch (severity) {
            case CRITICAL:
                return 2;
            case WARNING:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Honest fallback when no rule fires.
     *
     * <p>Saying "I don't know, but here is what moved" is far more useful than
     * inventing a cause, and it keeps the owner's trust for the cases where the
     * plugin <em>is</em> confident.
     */
    private Diagnosis unexplained(Incident incident, Baseline baseline) {
        Snapshot peak = incident.peak;
        List<Mover> movers = new ArrayList<>();

        for (Snapshot.WorldStats world : peak.worlds) {
            for (Map.Entry<String, Integer> e : world.entityCountsByType.entrySet()) {
                addMover(movers, world.name + "/" + e.getKey(), e.getValue(),
                        baseline.entities(world.name, e.getKey()));
            }
            for (Map.Entry<String, Integer> e
                    : world.blockEntityCountsByType.entrySet()) {
                addMover(movers, world.name + "/" + e.getKey() + " blocks",
                        e.getValue(),
                        baseline.blockEntities(world.name, e.getKey()));
            }
            addMover(movers, world.name + " loaded chunks", world.loadedChunks,
                    baseline.loadedChunks(world.name));
        }
        addMover(movers, "players online", peak.playerCount,
                baseline.playerCount);

        movers.sort(Comparator.comparingDouble((Mover m) -> m.delta).reversed());

        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < Math.min(3, movers.size()); i++) {
            evidence.add(movers.get(i).describe());
        }
        if (evidence.isEmpty()) {
            evidence.add("No tracked metric moved materially during the spike");
        }
        evidence.add("TPS fell to " + Stats.formatDouble(incident.worstTps(), 1)
                + " for " + Stats.formatDouble(incident.durationSeconds(), 0) + "s");

        return new Diagnosis("unexplained", 0.25, Diagnosis.Severity.WARNING,
                "Lag spike with no identifiable cause in tracked metrics",
                "Nothing measurable changed enough to explain this, which"
                        + " usually means the cost sat inside plugin code or"
                        + " world I/O rather than entity or chunk counts. Run"
                        + " spark profiling during the next occurrence, or"
                        + " check whether a scheduled task or backup runs at"
                        + " this time of day.",
                evidence.toArray(new String[0]));
    }

    private static void addMover(List<Mover> movers, String label,
                                 double observed, double base) {
        double delta = observed - base;
        if (delta >= 50.0) {
            movers.add(new Mover(label, observed, base, delta));
        }
    }

    private static final class Mover {
        final String label;
        final double observed;
        final double base;
        final double delta;

        Mover(String label, double observed, double base, double delta) {
            this.label = label;
            this.observed = observed;
            this.base = base;
            this.delta = delta;
        }

        String describe() {
            return label + ": " + Stats.formatCount(Math.round(observed))
                    + " versus a normal " + Stats.formatCount(Math.round(base));
        }
    }
}
