package com.example.refwhistle.domain;

import java.util.List;

/**
 * Delay resilience of a schedule ("blast radius").
 * For each match we simulate "this match starts D minutes late" with the real DelayPropagator and
 * count how many matches have to move. A schedule where everything is packed back to back on the
 * same ground or referee has a large blast radius; one with slack in the right places has a small one.
 * ScheduleCost.tightLinks() is the cheap proxy the optimiser uses; this is the true measurement.
 */
public class Robustness {

    public static class Stats {
        public final double average;   // mean matches affected by one delay
        public final int worst;        // worst single delay
        Stats(double average, int worst) {
            this.average = average;
            this.worst = worst;
        }
    }

    public static Stats blastRadius(List<Match> matches, Tournament t, int delayMin) {
        if (matches.isEmpty()) return new Stats(0, 0);
        long total = 0;
        int worst = 0;
        for (Match m : matches) {
            int n = DelayPropagator.propagate(matches, t, m.id, delayMin).affected();
            total += n;
            worst = Math.max(worst, n);
        }
        return new Stats((double) total / matches.size(), worst);
    }
}
