package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds the schedule from a sorted log. Deterministic: same log in, same schedule out.
 * Clash rule: the EARLIER operation keeps its slot; the later one is moved to the next
 * free slot (auto-reschedule) and recorded in 'rescheduled' so the UI can flag it.
 */
public class Replayer {

    public static class Result {
        public final Map<String, Match> matches = new LinkedHashMap<String, Match>();
        public final List<String> rescheduled = new ArrayList<String>();
        public final List<String> unplaceable = new ArrayList<String>();
        public final List<String> rejectedMoves = new ArrayList<String>();
        /** Matches that were moved at least once after being added (delay, rain, manual). */
        public final java.util.Set<String> moved = new java.util.TreeSet<String>();
        /** matchId -> {scoreA, scoreB}; last result in log order wins. */
        public final Map<String, int[]> results = new TreeMap<String, int[]>();
        /** matchId -> real kick-off / full-time (minutes after midnight), from referee events. */
        public final Map<String, Integer> actualStart = new TreeMap<String, Integer>();
        public final Map<String, Integer> actualEnd = new TreeMap<String, Integer>();

        private String resultsText() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : results.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()[0]).append('-')
                        .append(e.getValue()[1]).append(';');
            }
            return sb.toString();
        }

        /** Text fingerprint used by tests to compare two replicas. */
        public String fingerprint() {
            TreeMap<String, String> sorted = new TreeMap<String, String>();
            for (Match m : matches.values()) {
                sorted.put(m.id, m.toString());
            }
            return sorted.toString() + "|R" + new java.util.TreeSet<String>(rescheduled)
                    + "|U" + new java.util.TreeSet<String>(unplaceable)
                    + "|X" + new java.util.TreeSet<String>(rejectedMoves)
                    + "|S" + resultsText()
                    + "|K" + actualStart + "|F" + actualEnd;
        }
    }

    public static Result replay(List<Operation> sortedOps, Tournament t) {
        Result res = new Result();
        Map<String, Venue> vm = t.venueMap();

        for (Operation op : sortedOps) {
            switch (op.type) {
                case ADD_MATCH: {
                    if (res.matches.containsKey(op.matchId)) {
                        break; // already added
                    }
                    Match m = op.payload;
                    if (ConflictChecker.isFeasible(m, res.matches.values(), t)) {
                        res.matches.put(m.id, m);
                    } else {
                        Match moved = nextFreeSlot(m, res.matches.values(), t);
                        if (moved == null) {
                            res.unplaceable.add(m.id);
                        } else {
                            res.matches.put(m.id, moved);
                            res.rescheduled.add(m.id);
                        }
                    }
                    break;
                }
                case MOVE_MATCH: {
                    Match existing = res.matches.get(op.matchId);
                    if (existing == null) {
                        break; // match was cancelled or never added
                    }
                    Match m = op.payload;
                    if (ConflictChecker.isFeasible(m, res.matches.values(), t)) {
                        res.matches.put(m.id, m);
                        res.moved.add(m.id);
                    } else {
                        res.rejectedMoves.add(m.id); // keep old slot
                    }
                    break;
                }
                case CANCEL_MATCH:
                    res.matches.remove(op.matchId);
                    res.results.remove(op.matchId);
                    res.actualStart.remove(op.matchId);
                    res.actualEnd.remove(op.matchId);
                    break;
                case KICKOFF:
                case FULLTIME: {
                    if (res.matches.containsKey(op.matchId) && op.data != null) {
                        try {
                            int minute = Integer.parseInt(op.data);
                            if (op.type == Operation.Type.KICKOFF) res.actualStart.put(op.matchId, minute);
                            else res.actualEnd.put(op.matchId, minute);
                        } catch (NumberFormatException ignored) {
                            // malformed event from another phone
                        }
                    }
                    break;
                }
                case SET_RESULT: {
                    if (res.matches.containsKey(op.matchId) && op.data != null) {
                        String[] p = op.data.split("\\|");
                        res.results.put(op.matchId,
                                new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])});
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return res;
    }

    /** First feasible slot for this match, searching in a fixed order (so it is deterministic). */
    public static Match nextFreeSlot(Match m, Collection<Match> current, Tournament t) {
        Map<String, Venue> vm = t.venueMap();
        for (int day = 0; day < t.days; day++) {
            for (int start = 8 * 60; start + m.durationMin <= 22 * 60; start += m.durationMin) {
                for (Venue v : t.venues) {
                    for (Referee r : t.referees) {
                        Match cand = m.withSlot(day, start, v.id, r.id);
                        if (ConflictChecker.isFeasible(cand, current, t)) {
                            return cand;
                        }
                    }
                }
            }
        }
        return null;
    }
}
