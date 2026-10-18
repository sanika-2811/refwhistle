package com.example.refwhistle.domain;

/**
 * Hybrid logical clock timestamp: (physical time, counter, device id).
 * Total order: physical, then counter, then device id as the final tie-break,
 * so two different operations are never "equal".
 */
public class Stamp implements Comparable<Stamp> {
    public final long physical;
    public final int counter;
    public final String deviceId;

    public Stamp(long physical, int counter, String deviceId) {
        this.physical = physical;
        this.counter = counter;
        this.deviceId = deviceId;
    }

    public int compareTo(Stamp o) {
        if (physical != o.physical) {
            return physical < o.physical ? -1 : 1;
        }
        if (counter != o.counter) {
            return counter < o.counter ? -1 : 1;
        }
        return deviceId.compareTo(o.deviceId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Stamp && compareTo((Stamp) o) == 0;
    }

    @Override
    public int hashCode() {
        return (int) (physical ^ (physical >>> 32)) * 31 + counter * 17 + deviceId.hashCode();
    }

    @Override
    public String toString() {
        return physical + "." + counter + "@" + deviceId;
    }
}
