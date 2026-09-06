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
}
