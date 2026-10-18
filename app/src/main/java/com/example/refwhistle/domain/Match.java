package com.example.refwhistle.domain;

/**
 * One fixture. Immutable: moving a match creates a new Match with withSlot(...).
 * day is 0,1,2... ; startMin is minutes after midnight.
 */
public class Match {
    public final String id;
    public final String teamA;
    public final String teamB;
    public final String venueId;
    public final String refereeId;
    public final int day;
    public final int startMin;
    public final int durationMin;

    public Match(String id, String teamA, String teamB, String venueId, String refereeId,
                 int day, int startMin, int durationMin) {
        this.id = id;
        this.teamA = teamA;
        this.teamB = teamB;
        this.venueId = venueId;
        this.refereeId = refereeId;
        this.day = day;
        this.startMin = startMin;
        this.durationMin = durationMin;
    }

    public int endMin() {
        return startMin + durationMin;
    }

    public Match withSlot(int newDay, int newStartMin, String newVenueId, String newRefereeId) {
        return new Match(id, teamA, teamB, newVenueId, newRefereeId, newDay, newStartMin, durationMin);
    }

    public boolean hasTeam(String teamId) {
        return teamA.equals(teamId) || teamB.equals(teamId);
    }

    public boolean sharesTeamWith(Match other) {
        return hasTeam(other.teamA) || hasTeam(other.teamB);
    }

    /** Same day and the time ranges overlap: a.start < b.end AND b.start < a.end. */
    public boolean overlapsInTime(Match other) {
        return day == other.day && startMin < other.endMin() && other.startMin < endMin();
    }

    @Override
    public String toString() {
        return id + " " + teamA + "-" + teamB + " @" + venueId + " ref=" + refereeId
                + " d" + day + " " + startMin + "+" + durationMin;
    }
}
