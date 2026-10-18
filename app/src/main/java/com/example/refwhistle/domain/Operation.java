package com.example.refwhistle.domain;

/** One entry in the append-only log. Never edited or deleted once written. */
public class Operation {
    public enum Type { ADD_MATCH, MOVE_MATCH, CANCEL_MATCH, SET_RESULT, SET_LOGO, KICKOFF, FULLTIME }

    public final Stamp stamp;
    public final Type type;
    public final String matchId;
    public final Match payload; // full match for ADD/MOVE, null otherwise
    public final String data;   // "scoreA|scoreB" for SET_RESULT, null otherwise

    public Operation(Stamp stamp, Type type, String matchId, Match payload) {
        this(stamp, type, matchId, payload, null);
    }

    public Operation(Stamp stamp, Type type, String matchId, Match payload, String data) {
        this.stamp = stamp;
        this.type = type;
        this.matchId = matchId;
        this.payload = payload;
        this.data = data;
    }

    /** Unique id of this operation (stamps are unique because of the device id). */
    public String id() {
        return stamp.toString();
    }
}
