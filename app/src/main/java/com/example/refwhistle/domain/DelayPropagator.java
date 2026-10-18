package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live re-timing in both directions.
 *
 * propagate(): "match X starts D minutes late" pushes back every match that depends on it: the
 * next match at the same venue, with the same referee, or with either team (plus team rest).
 * Dependencies are followed transitively. Matches only ever move later.
 *
 * reclaim(): "match X finished E minutes early" pulls the matches that depend on X forward, but
 * never earlier than E minutes, never earlier than their other predecessors allow, and never
 * before the venue opens. Most tools only handle lateness and waste the saved time.
 *
 * Both return MOVE targets in an order that is feasible to apply one by one (the replayer checks
 * every move), flags for the admin, and notices per team/referee.
 */
public class DelayPropagator {

    public static class Result {
        /** Moves to log, in the order they must be applied. */
        public final List<Match> moves = new ArrayList<Match>();
        /** Human readable warnings, e.g. "M7 pushed past venue closing, moved to day 2". */
        public final List<String> flags = new ArrayList<String>();
        /** team id or referee id -> messages for local notifications. */
        public final Map<String, List<String>> notices = new LinkedHashMap<String, List<String>>();
        public int affected() {
            return moves.size();
        }
    }

    private static final int STEP = 15;

    private static List<Match> sorted(List<Match> current) {
        List<Match> order = new ArrayList<Match>(current);
        Collections.sort(order, new Comparator<Match>() {
            public int compare(Match a, Match b) {
                if (a.day != b.day) return a.day - b.day;
                if (a.startMin != b.startMin) return a.startMin - b.startMin;
                return a.id.compareTo(b.id);
            }
        });
        return order;
    }

    /** "Blue Lions vs Red Falcons": what users recognise, never the internal match id. */
    static String title(Tournament t, Match m) {
        return t.teamName(m.teamA) + " vs " + t.teamName(m.teamB);
    }

    private static int gapBetween(Match p, Match m, Tournament t) {
        int gap = -1;
        if (p.venueId.equals(m.venueId) || p.refereeId.equals(m.refereeId)) gap = 0;
        if (p.sharesTeamWith(m)) gap = Math.max(gap, t.minRestMin);
        return gap;
    }

    // ------------------------------------------------------------------ lateness

    public static Result propagate(List<Match> current, Tournament t, String lateMatchId, int delayMin) {
        Result res = new Result();
        final Map<String, Match> orig = new LinkedHashMap<String, Match>();
        List<Match> order = sorted(current);
        for (Match m : order) orig.put(m.id, m);
        if (!orig.containsKey(lateMatchId) || delayMin <= 0) {
            return res;
        }

        // 1. forward pass: earliest start for each match given its predecessors' new end times
        Map<String, Match> fin = new LinkedHashMap<String, Match>();
        for (int j = 0; j < order.size(); j++) {
            Match m = order.get(j);
            int start = m.startMin;
            if (m.id.equals(lateMatchId)) start += delayMin;
            for (int i = 0; i < j; i++) {
                Match p = fin.get(order.get(i).id);
                if (p.day != m.day) continue;
                int gap = gapBetween(p, m, t);
                if (gap >= 0) start = Math.max(start, p.endMin() + gap);
            }
            fin.put(m.id, m.withSlot(m.day, start, m.venueId, m.refereeId));
        }

        // 2. matches pushed past closing time go to the next free slot later in the tournament
        Map<String, Venue> vm = t.venueMap();
        for (Match m : order) {
            Match f = fin.get(m.id);
            Venue v = vm.get(f.venueId);
            if (v != null && f.endMin() > v.closeMin && f.startMin != m.startMin) {
                Match moved = laterSlot(f, others(fin, m.id), t);
                if (moved != null) {
                    fin.put(m.id, moved);
                    res.flags.add(title(t, m) + " would finish after " + v.name + " closes, so it moves to day "
                            + (moved.day + 1) + " at " + fmt(moved.startMin) + ".");
                } else {
                    res.flags.add(title(t, m) + " would finish after " + v.name
                            + " closes and no later slot is free. Please fix it by hand.");
                }
            }
        }

        emit(res, order, orig, fin, t, true);
        return res;
    }

    // ------------------------------------------------------------------ early finish

    /**
     * @param finishedId   the match that ended early
     * @param actualEndMin when it really ended (minutes after midnight)
     */
    public static Result reclaim(List<Match> current, Tournament t, String finishedId, int actualEndMin) {
        Result res = new Result();
        final Map<String, Match> orig = new LinkedHashMap<String, Match>();
        List<Match> order = sorted(current);
        for (Match m : order) orig.put(m.id, m);
        Match finished = orig.get(finishedId);
        if (finished == null) return res;
        int freed = finished.endMin() - actualEndMin;
        if (freed <= 0) return res;

        Map<String, Venue> vm = t.venueMap();
        Set<String> affected = new HashSet<String>();
        affected.add(finishedId);
        Map<String, Integer> endOf = new HashMap<String, Integer>();
        for (Match m : order) endOf.put(m.id, m.endMin());
        endOf.put(finishedId, actualEndMin);

        Map<String, Match> fin = new LinkedHashMap<String, Match>();
        for (int j = 0; j < order.size(); j++) {
            Match m = order.get(j);
            fin.put(m.id, m);
            if (m.id.equals(finishedId)) {
                // record the real length so the freed time is genuinely free in the schedule
                fin.put(m.id, new Match(m.id, m.teamA, m.teamB, m.venueId, m.refereeId, m.day, m.startMin,
                        Math.max(1, actualEndMin - m.startMin)));
                continue;
            }

            boolean dependent = false;
            for (int i = 0; i < j; i++) {
                Match p = order.get(i);
                if (affected.contains(p.id) && p.day == m.day && gapBetween(p, m, t) >= 0) {
                    dependent = true;
                    break;
                }
            }
            if (!dependent) continue;
            affected.add(m.id);

            Venue v = vm.get(m.venueId);
            int lower = v == null ? 8 * 60 : v.openMin;
            for (int i = 0; i < j; i++) {
                Match p = order.get(i);
                if (p.day != m.day) continue;
                int gap = gapBetween(p, m, t);
                if (gap >= 0) lower = Math.max(lower, endOf.get(p.id) + gap);
            }
            int target = Math.max(lower, m.startMin - freed);
            if (target < m.startMin) {
                fin.put(m.id, m.withSlot(m.day, target, m.venueId, m.refereeId));
                endOf.put(m.id, target + m.durationMin);
            }
        }

        boolean othersMove = false;
        for (Match m : order) {
            if (!m.id.equals(finishedId) && fin.get(m.id).startMin != m.startMin) othersMove = true;
        }
        if (!othersMove) return res;

        emit(res, order, orig, fin, t, false);
        return res;
    }

    // ------------------------------------------------------------------ shared

    /** Emits moves in an order that stays feasible when applied one by one, then builds notices. */
    private static void emit(Result res, List<Match> order, Map<String, Match> orig,
                             Map<String, Match> fin, Tournament t, boolean latestFirst) {
        List<Match> pending = new ArrayList<Match>();
        Map<String, Match> work = new LinkedHashMap<String, Match>(orig);
        for (int n = 0; n < order.size(); n++) {
            int k = latestFirst ? order.size() - 1 - n : n;
            Match o = order.get(k);
            Match f = fin.get(o.id);
            if (f.day != o.day || f.startMin != o.startMin || !f.venueId.equals(o.venueId)
                    || !f.refereeId.equals(o.refereeId) || f.durationMin != o.durationMin) {
                pending.add(f);
            }
        }
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (int k = 0; k < pending.size(); k++) {
                Match f = pending.get(k);
                if (ConflictChecker.isFeasible(f, work.values(), t)) {
                    work.put(f.id, f);
                    res.moves.add(f);
                    pending.remove(k);
                    progress = true;
                    break;
                }
            }
        }
        for (Match f : pending) {
            res.flags.add(title(t, f) + " could not be moved: "
                    + Conflict.describe(ConflictChecker.findConflicts(f, work.values(), t)) + ".");
        }

        for (Match f : res.moves) {
            Match o = orig.get(f.id);
            if (f.day == o.day && f.startMin == o.startMin && f.venueId.equals(o.venueId)) continue;  // only shortened
            String text = t.teamName(f.teamA) + " vs " + t.teamName(f.teamB) + " moved "
                    + fmt(o.startMin) + " -> " + fmt(f.startMin)
                    + (f.day != o.day ? " (day " + (f.day + 1) + ")" : "")
                    + " at " + t.venueName(f.venueId);
            notify(res, f.teamA, text);
            notify(res, f.teamB, text);
            notify(res, f.refereeId, text);
        }
    }

    private static void notify(Result res, String party, String text) {
        List<String> l = res.notices.get(party);
        if (l == null) {
            l = new ArrayList<String>();
            res.notices.put(party, l);
        }
        l.add(text);
    }

    private static List<Match> others(Map<String, Match> fin, String id) {
        List<Match> l = new ArrayList<Match>();
        for (Match m : fin.values()) {
            if (!m.id.equals(id)) l.add(m);
        }
        return l;
    }

    /** First feasible slot at or after the match's current day/time. Same venue and referee first. */
    public static Match laterSlot(Match m, List<Match> others, Tournament t) {
        List<Venue> venues = new ArrayList<Venue>(t.venues);
        List<Referee> refs = new ArrayList<Referee>(t.referees);
        for (int i = 0; i < venues.size(); i++) {
            if (venues.get(i).id.equals(m.venueId)) venues.add(0, venues.remove(i));
        }
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).id.equals(m.refereeId)) refs.add(0, refs.remove(i));
        }
        for (int day = m.day; day < t.days; day++) {
            int from = day == m.day ? m.startMin : 8 * 60;
            for (int s = from; s + m.durationMin <= 22 * 60; s += STEP) {
                for (Venue v : venues) {
                    for (Referee r : refs) {
                        Match c = m.withSlot(day, s, v.id, r.id);
                        if (ConflictChecker.isFeasible(c, others, t)) return c;
                    }
                }
            }
        }
        return null;
    }

    public static String fmt(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
