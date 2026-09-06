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
