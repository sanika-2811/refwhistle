package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft-rule cost: LOWER IS BETTER. Three terms, each with a weight you can explain in the viva:
 *  1. idle minutes between consecutive matches of the same team on the same day
 *  2. referee workload spread (busiest referee minus least busy referee)
 *  3. outdoor matches that start in the midday heat window
 */
public class ScheduleCost {
    public static final int HEAT_START = 12 * 60;  // 12:00
    public static final int HEAT_END = 15 * 60;    // 15:00
    public static final int W_IDLE = 1;
    public static final int W_REF_SPREAD = 100;
    public static final int W_HEAT = 200;
    /** Delay resilience: penalty per "tight link" (see tightLinks). */
    public static final int W_TIGHT = 30;
    /** Two matches on the same venue/referee closer than this are chained: a delay cascades. */
    public static final int SLACK_MIN = 15;

    public static int cost(List<Match> matches, Tournament t) {
        return cost(matches, t, W_TIGHT);
    }

    /** wTight = 0 reproduces the cost without the delay-resilience term (used for the ablation). */
    public static int cost(List<Match> matches, Tournament t, int wTight) {
        return W_IDLE * idleMinutes(matches, t)
                + W_REF_SPREAD * refereeSpread(matches, t)
                + W_HEAT * heatMatches(matches, t)
                + wTight * tightLinks(matches);
    }

    /**
     * Number of back-to-back pairs on the same venue or the same referee on one day with less
     * than SLACK_MIN between them. Each such link lets a delay cascade into the next match, so
     * fewer tight links means a smaller blast radius (measured exactly by Robustness).
     */
    public static int tightLinks(List<Match> matches) {
        Map<String, List<Match>> chains = new HashMap<String, List<Match>>();
        for (Match m : matches) {
            add(chains, "V" + m.day + "|" + m.venueId, m);
            add(chains, "R" + m.day + "|" + m.refereeId, m);
        }
        int tight = 0;
        for (List<Match> chain : chains.values()) {
            Collections.sort(chain, new Comparator<Match>() {
                public int compare(Match a, Match b) {
                    return a.startMin - b.startMin;
                }
            });
            for (int i = 1; i < chain.size(); i++) {
                if (chain.get(i).startMin - chain.get(i - 1).endMin() < SLACK_MIN) tight++;
            }
        }
        return tight;
    }

    private static void add(Map<String, List<Match>> map, String key, Match m) {
        List<Match> l = map.get(key);
        if (l == null) {
            l = new ArrayList<Match>();
            map.put(key, l);
        }
        l.add(m);
    }

    public static int idleMinutes(List<Match> matches, Tournament t) {
        int total = 0;
        for (Team team : t.teams) {
            for (int day = 0; day < t.days; day++) {
                List<Match> list = new ArrayList<Match>();
                for (Match m : matches) {
                    if (m.day == day && m.hasTeam(team.id)) {
                        list.add(m);
                    }
                }
                Collections.sort(list, new Comparator<Match>() {
                    public int compare(Match a, Match b) {
                        return a.startMin - b.startMin;
                    }
                });
                for (int i = 1; i < list.size(); i++) {
                    total += Math.max(0, list.get(i).startMin - list.get(i - 1).endMin());
                }
            }
        }
        return total;
    }

    public static int refereeSpread(List<Match> matches, Tournament t) {
        if (t.referees.isEmpty()) {
            return 0;
        }
        Map<String, Integer> load = new HashMap<String, Integer>();
        for (Referee r : t.referees) {
            load.put(r.id, 0);
        }
        for (Match m : matches) {
            Integer c = load.get(m.refereeId);
            load.put(m.refereeId, c == null ? 1 : c + 1);
        }
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int v : load.values()) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        return max - min;
    }

    public static int heatMatches(List<Match> matches, Tournament t) {
        Map<String, Venue> vm = t.venueMap();
        int n = 0;
        for (Match m : matches) {
            Venue v = vm.get(m.venueId);
            if (v != null && v.outdoor && m.startMin >= HEAT_START && m.startMin < HEAT_END) {
                n++;
            }
        }
        return n;
    }
}
