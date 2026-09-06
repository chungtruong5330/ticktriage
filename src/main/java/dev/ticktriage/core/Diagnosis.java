package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One explanation for an incident.
 *
 * <p>The whole product is this class. spark already tells an owner that their
 * server is slow; what they cannot do is read a flame graph. A diagnosis has to
 * carry a plain-language cause, the numbers backing it, and a fix specific
 * enough to act on without further research.
 */
public final class Diagnosis {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    public final String ruleId;
    public final double confidence;
    public final Severity severity;
    public final String headline;
    public final List<String> evidence;
    public final String suggestedFix;

    /**
     * Machine-readable target for the remediation planner, or null when this
     * rule has nothing specific enough to aim a fix at. Advice-only rules -
     * memory pressure, chronic overload - leave it null on purpose.
     */
    public final DiagnosisTarget target;

    public Diagnosis(String ruleId, double confidence, Severity severity,
                     String headline, List<String> evidence,
                     String suggestedFix) {
        this(ruleId, confidence, severity, headline, evidence, suggestedFix,
                null);
    }

    private Diagnosis(String ruleId, double confidence, Severity severity,
                      String headline, List<String> evidence,
                      String suggestedFix, DiagnosisTarget target) {
        this.ruleId = ruleId;
        this.confidence = Stats.clamp01(confidence);
        this.severity = severity;
        this.headline = headline;
        this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        this.suggestedFix = suggestedFix;
        this.target = target;
    }

    /** Copy carrying a structured target. Rules build the prose first, then
     *  attach what the planner needs. */
    public Diagnosis withTarget(DiagnosisTarget target) {
        return new Diagnosis(ruleId, confidence, severity, headline, evidence,
                suggestedFix, target);
    }

    public Diagnosis(String ruleId, double confidence, Severity severity,
                     String headline, String suggestedFix,
                     String... evidenceLines) {
        this(ruleId, confidence, severity, headline,
                Arrays.asList(evidenceLines), suggestedFix);
    }

    public int confidencePercent() {
        return (int) Math.round(confidence * 100);
    }

    /** Plain-text rendering, shared by the console, in-game chat and reports. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity).append("] ").append(headline)
                .append(" (").append(confidencePercent()).append("% confidence)");
        for (String line : evidence) {
            sb.append("\n  - ").append(line);
        }
        if (suggestedFix != null && !suggestedFix.isEmpty()) {
            sb.append("\n  Fix: ").append(suggestedFix);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return ruleId + "@" + confidencePercent() + "%";
    }
}
