package com.example.refwhistle.sim;

import com.example.refwhistle.domain.ConflictChecker;
import com.example.refwhistle.domain.DelayPropagator;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.HybridLogicalClock;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.OpLog;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Robustness;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Schedule;
import com.example.refwhistle.domain.ScheduleCost;
import com.example.refwhistle.domain.SimulatedAnnealing;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Plain-Java experiments for the report. Run SimMain; no Android needed. */
public class Simulator {

    public static Tournament randomTournament(int teams, int venues, int referees, int days, long seed) {
        Random rnd = new Random(seed);
        Tournament t = new Tournament();
        for (int i = 1; i <= teams; i++) t.teams.add(new Team("T" + i, "Team " + i));
        for (int i = 1; i <= venues; i++) {
            boolean outdoor = rnd.nextInt(3) != 0;
            t.venues.add(new Venue("V" + i, "Venue " + i, outdoor, 8 * 60, 18 * 60 + rnd.nextInt(3) * 60));
        }
        for (int i = 1; i <= referees; i++) t.referees.add(new Referee("R" + i, "Ref " + i, "T" + i));
        t.days = days;
        return t;
    }

    /** True if no hard rule is broken anywhere in the schedule. */
    public static boolean allFeasible(List<Match> ms, Tournament t) {
        for (Match m : ms) {
            if (!ConflictChecker.isFeasible(m, ms, t)) return false;
        }
        return true;
    }

    public static class QualityRow {
        public int teams, greedyCost, saCost, greedySpread, saSpread, greedyHeat, saHeat, unplaced;
        public boolean feasible;
    }

    public static QualityRow quality(int teams, long seed) {
        Tournament t = randomTournament(teams, 3, 4, 3, seed);
        Schedule g = GreedyScheduler.schedule(t);
        List<Match> better = SimulatedAnnealing.improve(g.matches, t, g.matches.size() * 2000, seed);
        QualityRow r = new QualityRow();
        r.teams = teams;
        r.unplaced = g.unplaced.size();
        r.greedyCost = ScheduleCost.cost(g.matches, t);
        r.saCost = ScheduleCost.cost(better, t);
        r.greedySpread = ScheduleCost.refereeSpread(g.matches, t);
        r.saSpread = ScheduleCost.refereeSpread(better, t);
        r.greedyHeat = ScheduleCost.heatMatches(g.matches, t);
        r.saHeat = ScheduleCost.heatMatches(better, t);
        r.feasible = allFeasible(better, t);
        return r;
    }

    /** Three devices with skewed wall clocks make random edits and gossip; returns the final logs. */
    public static List<OpLog> randomDevices(Tournament t, long seed, int opsPerDevice) {
        Random rnd = new Random(seed);
        int n = 3;
        List<HybridLogicalClock> clocks = new ArrayList<HybridLogicalClock>();
        List<OpLog> logs = new ArrayList<OpLog>();
        long[] skew = {0, 7000, -5000};
        for (int d = 0; d < n; d++) {
            clocks.add(new HybridLogicalClock("dev" + d));
            logs.add(new OpLog());
        }
        long wall = 1700000000000L;
        List<String> known = new ArrayList<String>();
        int counter = 0;
        for (int step = 0; step < opsPerDevice * n; step++) {
            wall += rnd.nextInt(2000);
            int d = rnd.nextInt(n);
            long now = wall + skew[d];
            Operation op;
            int kind = rnd.nextInt(10);
            if (known.isEmpty() || kind < 6) {
                String a = "T" + (1 + rnd.nextInt(t.teams.size()));
                String b = "T" + (1 + rnd.nextInt(t.teams.size()));
                if (a.equals(b)) continue;
                String id = "M" + (++counter);
                Match m = new Match(id, a, b, "V" + (1 + rnd.nextInt(t.venues.size())),
                        "R" + (1 + rnd.nextInt(t.referees.size())),
                        rnd.nextInt(t.days), 8 * 60 + rnd.nextInt(8) * 60, 60);
                known.add(id);
                op = new Operation(clocks.get(d).tick(now), Operation.Type.ADD_MATCH, id, m);
            } else if (kind < 9) {
                String id = known.get(rnd.nextInt(known.size()));
                Match m = new Match(id, "T1", "T2", "V" + (1 + rnd.nextInt(t.venues.size())),
                        "R" + (1 + rnd.nextInt(t.referees.size())),
                        rnd.nextInt(t.days), 8 * 60 + rnd.nextInt(8) * 60, 60);
                op = new Operation(clocks.get(d).tick(now), Operation.Type.MOVE_MATCH, id, m);
            } else {
                String id = known.get(rnd.nextInt(known.size()));
                op = new Operation(clocks.get(d).tick(now), Operation.Type.SET_RESULT, id, null,
                        rnd.nextInt(5) + "|" + rnd.nextInt(5));
            }
            logs.get(d).append(op);
            // occasionally two devices sync (and their clocks observe each other's stamps)
            if (rnd.nextInt(6) == 0) {
                int e = rnd.nextInt(n);
                if (e != d) {
                    for (Operation o : logs.get(e).all()) clocks.get(d).receive(o.stamp, now);
                    for (Operation o : logs.get(d).all()) clocks.get(e).receive(o.stamp, now);
                    List<Operation> merged = logs.get(d).all();
                    merged.addAll(logs.get(e).all());
                    logs.get(d).merge(merged);
                    logs.get(e).merge(merged);
                }
            }
        }
        return logs;
    }

    /** Replays the same operations in `trials` shuffled arrival orders; returns distinct outcomes. */
    public static int distinctOutcomes(Tournament t, long seed, int trials) {
        List<OpLog> logs = randomDevices(t, seed, 40);
        List<Operation> all = new ArrayList<Operation>();
        OpLog union = new OpLog();
        for (OpLog l : logs) union.merge(l.all());
        all.addAll(union.all());
        java.util.Set<String> prints = new java.util.HashSet<String>();
        Random rnd = new Random(seed + 1);
        for (int i = 0; i < trials; i++) {
            Collections.shuffle(all, rnd);
            OpLog log = new OpLog();
            // arrive in several shuffled chunks, with duplicates, like repeated syncs
            for (int k = 0; k < all.size(); k += 7) {
                log.merge(all.subList(k, Math.min(all.size(), k + 7)));
                log.merge(all.subList(0, Math.min(all.size(), 3)));
            }
            prints.add(Replayer.replay(log.sorted(), t).fingerprint());
        }
        return prints.size();
    }

    /** Max-min referee workload across random tournaments. */
    public static int worstSpread(boolean greedy, int runs) {
        int worst = 0;
        for (int i = 0; i < runs; i++) {
            Tournament t = randomTournament(8 + i % 9, 3, 4, 3, 100 + i);
            Schedule g = GreedyScheduler.schedule(t);
            List<Match> ms = greedy ? g.matches
                    : SimulatedAnnealing.improve(g.matches, t, g.matches.size() * 2000, 100 + i);
            worst = Math.max(worst, ScheduleCost.refereeSpread(ms, t));
        }
        return worst;
    }

    /** Milliseconds to recompute a 16-team schedule after a delay (average of runs). */
    public static double delayMillis(int runs) {
        Tournament t = randomTournament(16, 4, 6, 4, 7);
        Schedule g = GreedyScheduler.schedule(t);
        Match first = g.matches.get(0);
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            DelayPropagator.propagate(g.matches, t, first.id, 15);
        }
        return (System.nanoTime() - start) / 1e6 / runs;
    }

    public static class Resilience {
        public double greedyAvg, plainAvg, robustAvg;       // mean matches moved by one 15-min delay
        public int greedyWorst, plainWorst, robustWorst;    // worst single delay
        public int plainCost, robustCost;                    // cost with the NEW term, same yardstick
    }

    /** Greedy vs optimiser WITHOUT the resilience term vs optimiser WITH it, judged by the real propagator. */
    public static Resilience resilience(int teams, long seed) {
        Tournament t = randomTournament(teams, 3, 4, 3, seed);
        Schedule g = GreedyScheduler.schedule(t);
        int iters = g.matches.size() * 2000;
        List<Match> plain = SimulatedAnnealing.improve(g.matches, t, iters, seed, 0);
        List<Match> robust = SimulatedAnnealing.improve(g.matches, t, iters, seed);
        Resilience r = new Resilience();
        Robustness.Stats a = Robustness.blastRadius(g.matches, t, 15);
        Robustness.Stats b = Robustness.blastRadius(plain, t, 15);
        Robustness.Stats c = Robustness.blastRadius(robust, t, 15);
        r.greedyAvg = a.average; r.greedyWorst = a.worst;
        r.plainAvg = b.average; r.plainWorst = b.worst;
        r.robustAvg = c.average; r.robustWorst = c.worst;
        r.plainCost = ScheduleCost.cost(plain, t);
        r.robustCost = ScheduleCost.cost(robust, t);
        return r;
    }
}
