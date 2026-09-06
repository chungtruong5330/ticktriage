package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded rolling window of samples.
 *
 * <p>Capacity is in samples, not minutes, so memory use is fixed and knowable:
 * this plugin exists to diagnose resource problems and would be worthless if it
 * caused one. At one sample per second, the default holds two hours.
 */
public final class History {

    public static final int DEFAULT_CAPACITY = 7200;

    private final Deque<Snapshot> samples = new ArrayDeque<>();
    private final int capacity;

    public History() {
        this(DEFAULT_CAPACITY);
    }

    public History(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void add(Snapshot snapshot) {
        samples.addLast(snapshot);
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized boolean isEmpty() {
        return samples.isEmpty();
    }

    public synchronized List<Snapshot> samples() {
        return new ArrayList<>(samples);
    }

    public synchronized Snapshot latest() {
        return samples.isEmpty() ? null : samples.getLast();
    }

    /** Samples outside the incident window, for computing an honest baseline. */
    public synchronized List<Snapshot> samplesExcluding(Incident incident) {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : samples) {
            if (s.timestampMillis < incident.startMillis
                    || s.timestampMillis > incident.endMillis) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized List<Snapshot> since(long timestampMillis) {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : samples) {
            if (s.timestampMillis >= timestampMillis) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized void clear() {
        samples.clear();
    }
}
