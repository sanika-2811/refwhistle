package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns live referee events (kick-off, full time) into suggestions for the admin, and learns the
 * real length of matches. Everything is derived from the replayed log, so it works the same on
 * every phone and needs no extra state: a suggestion disappears by itself once its moves are applied.
 */
public class LiveOps {
    public static final int LATE_TOLERANCE_MIN = 5;
    public static final int EARLY_MIN = 10;

    public enum Kind { LATE, EARLY }

    public static class Suggestion {
        public final Kind kind;
        public final String matchId;
        public final int minutes;
        public final DelayPropagator.Result plan;
        public final String text;

        Suggestion(Kind kind, String matchId, int minutes, DelayPropagator.Result plan, String text) {
            this.kind = kind;
            this.matchId = matchId;
            this.minutes = minutes;
            this.plan = plan;
            this.text = text;
        }

        /** Stable id so a dismissed suggestion stays dismissed. */
        public String key() {
            return kind + ":" + matchId + ":" + minutes;
        }
    }

    public static List<Suggestion> suggest(Replayer.Result st, List<Match> matches, Tournament t) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        for (Match m : matches) {
            String title = t.teamName(m.teamA) + " vs " + t.teamName(m.teamB);
            Integer ks = st.actualStart.get(m.id);
            if (ks != null && ks - m.startMin >= LATE_TOLERANCE_MIN) {
                int late = ks - m.startMin;
                DelayPropagator.Result plan = DelayPropagator.propagate(matches, t, m.id, late);
                if (!plan.moves.isEmpty()) {
                    int following = plan.affected() - 1;
                    out.add(new Suggestion(Kind.LATE, m.id, late, plan, title + " started " + late
                            + " min late." + (following > 0 ? " Re-time the next " + following
                            + (following == 1 ? " match?" : " matches?") : " Update its time?")));
                }
            }
            Integer fe = st.actualEnd.get(m.id);
            if (fe != null && m.endMin() - fe >= EARLY_MIN) {
                int early = m.endMin() - fe;
                DelayPropagator.Result plan = DelayPropagator.reclaim(matches, t, m.id, fe);
                if (!plan.moves.isEmpty()) {
                    int following = plan.affected() - 1;
                    out.add(new Suggestion(Kind.EARLY, m.id, early, plan, title + " finished " + early
                            + " min early. Move the next " + following + (following == 1 ? " match" : " matches")
                            + " earlier?"));
                }
            }
        }
        return out;
    }

    /** {average minutes, number of matches measured}; average is -1 when there is no data. */
    public static int[] observedLength(Replayer.Result st) {
        int sum = 0, n = 0;
        for (Map.Entry<String, Integer> e : st.actualEnd.entrySet()) {
            Integer s = st.actualStart.get(e.getKey());
            if (s == null) continue;
            int d = e.getValue() - s;
            if (d < 10 || d > 300) continue;     // ignore obvious mistakes
            sum += d;
            n++;
        }
        return new int[]{n == 0 ? -1 : Math.round((float) sum / n), n};
    }

    /** Planned length for the next schedule: learned average rounded to 5 min (needs 3+ matches). */
    public static int learnedMatchMin(Replayer.Result st, int fallback) {
        int[] o = observedLength(st);
        if (o[1] < 3) return fallback;
        int r = Math.round(o[0] / 5f) * 5;
        return Math.max(30, Math.min(180, r));
    }
}
