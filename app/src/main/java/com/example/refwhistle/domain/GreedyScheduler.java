package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline scheduler. Builds round-robin pairings (circle method), then puts each pairing
 * in the EARLIEST feasible slot, giving it the referee with the fewest matches so far.
 * It never breaks a hard rule. The improver (SimulatedAnnealing) tries to beat its cost.
 */
public class GreedyScheduler {

    /** Round-robin pairings using the circle method. Each element is {teamIdA, teamIdB}. */
    public static List<String[]> roundRobin(List<Team> teams) {
        List<String> ids = new ArrayList<String>();
        for (Team t : teams) {
            ids.add(t.id);
        }
        if (ids.size() % 2 == 1) {
            ids.add(null); // bye
        }
        int n = ids.size();
        List<String[]> pairs = new ArrayList<String[]>();
        for (int round = 0; round < n - 1; round++) {
            for (int i = 0; i < n / 2; i++) {
                String a = ids.get(i);
                String b = ids.get(n - 1 - i);
                if (a != null && b != null) {
                    pairs.add(new String[]{a, b});
                }
            }
            // rotate every team except the first one
            ids.add(1, ids.remove(n - 1));
        }
        return pairs;
    }

    public static Schedule schedule(Tournament t) {
        Schedule s = new Schedule();
        Map<String, Venue> vm = t.venueMap();
        Map<String, Integer> refLoad = new HashMap<String, Integer>();
        for (Referee r : t.referees) {
            refLoad.put(r.id, 0);
        }

        int counter = 1;
        for (String[] p : roundRobin(t.teams)) {
            String id = "M" + counter++;
            Match placed = null;

            search:
            for (int day = 0; day < t.days; day++) {
                for (int start = 8 * 60; start + t.matchMin <= 22 * 60; start += t.matchMin) {
                    for (Venue v : t.venues) {
                        for (Referee r : refereesByLoad(t, refLoad)) {
                            Match cand = new Match(id, p[0], p[1], v.id, r.id, day, start, t.matchMin);
                            if (ConflictChecker.isFeasible(cand, s.matches, t)) {
                                placed = cand;
                                break search;
                            }
                        }
                    }
                }
            }

            if (placed == null) {
                s.unplaced.add(p[0] + " vs " + p[1]);
            } else {
                s.matches.add(placed);
                refLoad.put(placed.refereeId, refLoad.get(placed.refereeId) + 1);
            }
        }
        return s;
    }

    private static List<Referee> refereesByLoad(Tournament t, final Map<String, Integer> load) {
        List<Referee> list = new ArrayList<Referee>(t.referees);
        java.util.Collections.sort(list, new java.util.Comparator<Referee>() {
            public int compare(Referee a, Referee b) {
                int d = load.get(a.id) - load.get(b.id);
                return d != 0 ? d : a.id.compareTo(b.id);
            }
        });
        return list;
    }
}
