package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Local search on top of a feasible schedule. Repeatedly picks one match and tries a random new
 * slot (day, start time, venue, referee). The move is only considered if ALL hard rules still hold.
 * A worse move is sometimes accepted early on (temperature) so the search can escape local minima.
 * Fixed seed => same answer every run, which makes results reproducible for the report.
 */
public class SimulatedAnnealing {

    public static List<Match> improve(List<Match> start, Tournament t, int iterations, long seed) {
        return improve(start, t, iterations, seed, ScheduleCost.W_TIGHT);
    }

    public static List<Match> improve(List<Match> start, Tournament t, int iterations, long seed, int wTight) {
        Random rnd = new Random(seed);
        Map<String, Venue> vm = t.venueMap();
        List<Match> cur = new ArrayList<Match>(start);
        List<Match> best = new ArrayList<Match>(start);
        int curCost = ScheduleCost.cost(cur, t, wTight);
        int bestCost = curCost;
        int slotsPerDay = (22 * 60 - 8 * 60) / t.matchMin;

        for (int it = 0; it < iterations; it++) {
            double temp = 200.0 * (1.0 - (double) it / iterations) + 1.0;
            int idx = rnd.nextInt(cur.size());
            Match old = cur.get(idx);

            int day = rnd.nextInt(t.days);
            int startMin = 8 * 60 + rnd.nextInt(slotsPerDay) * t.matchMin;
            Venue v = t.venues.get(rnd.nextInt(t.venues.size()));
            Referee r = t.referees.get(rnd.nextInt(t.referees.size()));
            Match cand = old.withSlot(day, startMin, v.id, r.id);

            // check against every OTHER match (ConflictChecker skips the same id)
            if (!ConflictChecker.isFeasible(cand, cur, t)) {
                continue;
            }
            List<Match> next = new ArrayList<Match>(cur);
            next.set(idx, cand);
            int nextCost = ScheduleCost.cost(next, t, wTight);
            int delta = nextCost - curCost;
            if (delta <= 0 || rnd.nextDouble() < Math.exp(-delta / temp)) {
                cur = next;
                curCost = nextCost;
                if (curCost < bestCost) {
                    bestCost = curCost;
                    best = new ArrayList<Match>(cur);
                }
            }
        }
        return best;
    }
}
