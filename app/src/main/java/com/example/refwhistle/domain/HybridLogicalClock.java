package com.example.refwhistle.domain;

/**
 * Hybrid logical clock (Kulkarni et al.). It stays close to real time but never goes backwards,
 * and after receive() every new local stamp is greater than anything seen from other devices,
 * even if the phones' wall clocks disagree.
 * wallMillis is passed in so tests can control time; on Android pass System.currentTimeMillis().
 */
public class HybridLogicalClock {
    private final String deviceId;
    private long l = 0;
    private int c = 0;

    public HybridLogicalClock(String deviceId) {
        this.deviceId = deviceId;
    }

    /** Call before writing a local operation. */
    public synchronized Stamp tick(long wallMillis) {
        if (wallMillis > l) {
            l = wallMillis;
            c = 0;
        } else {
            c++;
        }
        return new Stamp(l, c, deviceId);
    }

    /** Call when an operation from another device arrives. */
    public synchronized Stamp receive(Stamp remote, long wallMillis) {
        long newL = Math.max(Math.max(l, remote.physical), wallMillis);
        if (newL == l && newL == remote.physical) {
            c = Math.max(c, remote.counter) + 1;
        } else if (newL == l) {
            c = c + 1;
        } else if (newL == remote.physical) {
            c = remote.counter + 1;
        } else {
            c = 0;
        }
        l = newL;
        return new Stamp(l, c, deviceId);
    }
}
