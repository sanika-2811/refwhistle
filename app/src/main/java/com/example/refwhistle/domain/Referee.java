package com.example.refwhistle.domain;

public class Referee {
    public final String id;
    public final String name;
    /** Team this referee also belongs to (conflict of interest), or "" if none. */
    public final String teamId;

    public Referee(String id, String name) {
        this(id, name, "");
    }

    public Referee(String id, String name, String teamId) {
        this.id = id;
        this.name = name;
        this.teamId = teamId == null ? "" : teamId;
    }
}
