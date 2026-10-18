package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything the scheduler needs to know: the people, the places and the rules. */
public class Tournament {
    public final List<Team> teams = new ArrayList<Team>();
    public final List<Venue> venues = new ArrayList<Venue>();
    public final List<Referee> referees = new ArrayList<Referee>();
    public int days = 2;
    public int matchMin = 60;     // length of every match
    public int minRestMin = 30;   // minimum rest between two matches of the same team on a day

    private Map<String, Venue> venueCache;
    private int venueCacheSize = -1;
    private Map<String, String> refTeamCache;
    private int refCacheSize = -1;

    /** Cached lookup (rebuilt when the list size changes); do not modify the returned map. */
    public Map<String, Venue> venueMap() {
        if (venueCache == null || venueCacheSize != venues.size()) {
            Map<String, Venue> m = new HashMap<String, Venue>();
            for (Venue v : venues) {
                m.put(v.id, v);
            }
            venueCache = m;
            venueCacheSize = venues.size();
        }
        return venueCache;
    }

    /** refereeId -> the team that referee belongs to ("" if none). */
    public Map<String, String> refereeTeamMap() {
        if (refTeamCache == null || refCacheSize != referees.size()) {
            Map<String, String> m = new HashMap<String, String>();
            for (Referee r : referees) {
                m.put(r.id, r.teamId);
            }
            refTeamCache = m;
            refCacheSize = referees.size();
        }
        return refTeamCache;
    }

    public String teamName(String id) {
        for (Team t : teams) {
            if (t.id.equals(id)) return t.name;
        }
        return id;
    }

    public String venueName(String id) {
        for (Venue v : venues) {
            if (v.id.equals(id)) return v.name;
        }
        return id;
    }

    public String refereeName(String id) {
        for (Referee r : referees) {
            if (r.id.equals(id)) return r.name;
        }
        return id;
    }
}
