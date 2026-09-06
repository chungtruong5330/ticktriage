package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A contiguous stretch where the server ran slower than it normally does. */
public final class Incident {

    public final long startMillis;
    public final long endMillis;
    public final List<Snapshot> samples;
    /** The single worst sample - what the rules interrogate. */
    public final Snapshot peak;

    public Incident(List<Snapshot> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("an incident needs samples");
        }
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.startMillis = this.samples.get(0).timestampMillis;
        this.endMillis = this.samples.get(this.samples.size() - 1).timestampMillis;

        Snapshot worst = this.samples.get(0);
        for (Snapshot s : this.samples) {
            if (s.msPerTick > worst.msPerTick) {
                worst = s;
            }
        }
        this.peak = worst;
    }

    public long durationMillis() {
        return endMillis - startMillis;
    }

    public double durationSeconds() {
        return durationMillis() / 1000.0;
    }

    public double worstTps() {
        return peak.tps();
    }

    /**
     * How bad it got, in whichever unit is actually meaningful.
     *
     * <p>A tick under 50 ms still reports 20 TPS, so on a server that went from
     * 0.2 ms to 30 ms ticks the honest statement is that tick time rose - not
     * "TPS fell to 20.0", which is what this used to say and is nonsense.
     */
    public String describeImpact() {
        if (peak.msPerTick > 50.0) {
            return "TPS fell to " + Stats.formatDouble(worstTps(), 1);
        }
        return "tick time rose to "
                + Stats.formatDouble(peakMsPerTick(), 0) + " ms";
    }

    public double peakMsPerTick() {
        return peak.msPerTick;
    }

    /** Mean tick time across the incident, for describing severity honestly. */
    public double averageMsPerTick() {
        double sum = 0.0;
        for (Snapshot s : samples) {
            sum += s.msPerTick;
        }
        return sum / samples.size();
    }

    /** The sample immediately before things went wrong, when we have it. */
    public Snapshot firstSample() {
        return samples.get(0);
    }
}
