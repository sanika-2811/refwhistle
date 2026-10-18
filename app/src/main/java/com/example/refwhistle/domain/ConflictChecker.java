package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Hard-rule checker. A candidate match is checked against all other matches.
 * Hard rules: no venue, referee or team double-booking; teams get minimum rest;
 * the match must lie inside the venue's opening hours.
 */
public class ConflictChecker {

    public static List<Conflict> findConflicts(Match m, Collection<Match> others, Tournament t) {
        return findConflicts(m, others, t.venueMap(), t.minRestMin, t.refereeTeamMap());
    }

    public static boolean isFeasible(Match m, Collection<Match> others, Tournament t) {
        return findConflicts(m, others, t).isEmpty();
    }

    public static List<Conflict> findConflicts(Match m, Collection<Match> others,
                                                Map<String, Venue> venues, int minRestMin) {
        return findConflicts(m, others, venues, minRestMin, null);
    }

    public static List<Conflict> findConflicts(Match m, Collection<Match> others,
                                                Map<String, Venue> venues, int minRestMin,
                                                Map<String, String> refereeTeam) {
        List<Conflict> out = new ArrayList<Conflict>();

        // fairness rule: a referee never officiates a match of their own team
        String own = refereeTeam == null ? null : refereeTeam.get(m.refereeId);
        if (own != null && !own.isEmpty() && m.hasTeam(own)) {
            out.add(new Conflict(Conflict.Type.CONFLICT_OF_INTEREST, null));
        }

        Venue v = venues.get(m.venueId);
        if (v == null || m.startMin < v.openMin || m.endMin() > v.closeMin) {
            out.add(new Conflict(Conflict.Type.HOURS, null));
        }

        for (Match o : others) {
            if (o.id.equals(m.id)) {
                continue; // do not compare a match with itself
            }
            if (m.overlapsInTime(o)) {
                if (m.venueId.equals(o.venueId)) {
                    out.add(new Conflict(Conflict.Type.VENUE, o.id));
                }
                if (m.refereeId.equals(o.refereeId)) {
                    out.add(new Conflict(Conflict.Type.REFEREE, o.id));
                }
                if (m.sharesTeamWith(o)) {
                    out.add(new Conflict(Conflict.Type.TEAM, o.id));
                }
            } else if (m.day == o.day && m.sharesTeamWith(o)) {
                // not overlapping, but is the rest between them long enough?
                int gap = m.startMin >= o.endMin() ? m.startMin - o.endMin() : o.startMin - m.endMin();
                if (gap < minRestMin) {
                    out.add(new Conflict(Conflict.Type.REST, o.id));
                }
            }
        }
        return out;
    }

    public static boolean isFeasible(Match m, Collection<Match> others,
                                     Map<String, Venue> venues, int minRestMin) {
        return findConflicts(m, others, venues, minRestMin).isEmpty();
    }
}
