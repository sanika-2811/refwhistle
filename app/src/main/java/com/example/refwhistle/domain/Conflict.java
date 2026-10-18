package com.example.refwhistle.domain;

/** One broken hard rule, e.g. "VENUE clash with M3". */
public class Conflict {
    public enum Type { VENUE, REFEREE, TEAM, REST, HOURS, CONFLICT_OF_INTEREST }

    public final Type type;
    public final String otherMatchId; // null for HOURS

    public Conflict(Type type, String otherMatchId) {
        this.type = type;
        this.otherMatchId = otherMatchId;
    }

    /** Plain-language reason, safe to show to users. */
    public String userMessage() {
        switch (type) {
            case VENUE: return "The venue is already booked at that time";
            case REFEREE: return "That referee is already refereeing another match at that time";
            case TEAM: return "One of the teams is already playing at that time";
            case REST: return "One of the teams needs more rest between matches";
            case HOURS: return "The venue is closed at that time";
            case CONFLICT_OF_INTEREST: return "That referee plays for one of the teams in this match";
            default: return toString();
        }
    }

    public static String describe(java.util.List<Conflict> conflicts) {
        StringBuilder sb = new StringBuilder();
        for (Conflict c : conflicts) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.userMessage());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        if (type == Type.CONFLICT_OF_INTEREST) return "REFEREE is from one of the playing teams";
        return type + (otherMatchId == null ? "" : " clash with " + otherMatchId);
    }
}
