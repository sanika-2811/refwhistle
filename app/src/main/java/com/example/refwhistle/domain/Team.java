package com.example.refwhistle.domain;

/** A team (or player group) taking part in the tournament. Pure Java: no Android imports. */
public class Team {
    public final String id;
    public final String name;

    public Team(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
