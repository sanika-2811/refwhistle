package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rain (or any outdoor closure) planner. Every match at an OUTDOOR venue that overlaps the closed
 * window on that day is moved to the nearest later slot that is not an outdoor venue inside the
 * window. Indoor venues are preferred automatically because the search runs in time order and
 * accepts the first feasible slot. Moves are applied one at a time to a working copy, so every
 * emitted move is valid when the replayer applies it in the same order.
 */
public class WeatherPlanner {

    public static class Result {
        public final List<Match> moves = new ArrayList<Match>();
        public final List<String> flags = new ArrayList<String>();
        public final List<Match> before = new ArrayList<Match>();
    }

    public static Result plan(List<Match> current, Tournament t, int day, int fromMin, int toMin) {
        Result res = new Result();
        Map<String, Match> work = new LinkedHashMap<String, Match>();
        for (Match m : current) work.put(m.id, m);
        Map<String, Venue> vm = t.venueMap();

        List<Match> hit = new ArrayList<Match>();
        for (Match m : current) {
            Venue v = vm.get(m.venueId);
            if (v != null && v.outdoor && closed(m, day, fromMin, toMin)) hit.add(m);
        }
        Collections.sort(hit, new Comparator<Match>() {
            public int compare(Match a, Match b) {
                if (a.startMin != b.startMin) return a.startMin - b.startMin;
                return a.id.compareTo(b.id);
            }
        });

        for (Match m : hit) {
            Match found = null;
            search:
            for (int d = m.day; d < t.days; d++) {
                int from = d == m.day ? m.startMin : 8 * 60;
                for (int s = from; s + m.durationMin <= 22 * 60; s += 15) {
                    for (Venue v : t.venues) {
                        for (Referee r : t.referees) {
                            Match c = m.withSlot(d, s, v.id, r.id);
                            if (v.outdoor && closed(c, day, fromMin, toMin)) continue;
                            List<Match> others = new ArrayList<Match>(work.values());
                            others.remove(work.get(m.id));
                            if (ConflictChecker.isFeasible(c, others, t)) {
                                found = c;
                                break search;
                            }
                        }
                    }
                }
            }
            if (found == null) {
                res.flags.add(t.teamName(m.teamA) + " vs " + t.teamName(m.teamB)
                        + " has no free slot after the closure. Please fix it by hand.");
            } else {
                work.put(m.id, found);
                res.before.add(m);
                res.moves.add(found);
            }
        }
        return res;
    }

    private static boolean closed(Match m, int day, int fromMin, int toMin) {
        return m.day == day && m.startMin < toMin && fromMin < m.endMin();
    }
}
