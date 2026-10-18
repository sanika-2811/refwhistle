package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.List;

/** When the scheduler cannot place every match, say why in plain words. */
public class Diagnosis {
    public static List<String> explain(Tournament t, Schedule s) {
        List<String> out = new ArrayList<String>();
        if (s.unplaced.isEmpty()) return out;
        int n = t.teams.size();
        int needed = n * (n - 1) / 2;
        int dayMin = 14 * 60; // scheduler window 08:00-22:00
        int slotsPerDay = dayMin / t.matchMin;

        int venueSlots = 0;
        for (Venue v : t.venues) {
            int open = Math.max(v.openMin, 8 * 60), close = Math.min(v.closeMin, 22 * 60);
            venueSlots += Math.max(0, (close - open) / t.matchMin);
        }
        venueSlots *= t.days;
        if (needed > venueSlots) {
            out.add("Venues: " + needed + " matches are needed but the venues only have " + venueSlots
                    + " match slots in " + t.days + " day(s). Add a venue, a day, or shorten matches.");
        }
        int refSlots = t.referees.size() * slotsPerDay * t.days;
        if (needed > refSlots) {
            out.add("Referees: " + t.referees.size() + " referee(s) can cover at most " + refSlots
                    + " matches, but " + needed + " are needed. Add referees.");
        }
        int perDay = (dayMin + t.minRestMin) / (t.matchMin + t.minRestMin);
        if (n - 1 > perDay * t.days) {
            out.add("Teams: each team plays " + (n - 1) + " matches but with " + t.minRestMin
                    + " min rest can play at most " + perDay * t.days + " in " + t.days
                    + " day(s). Add days or reduce rest time.");
        }
        if (out.isEmpty()) {
            out.add("Capacity is enough on paper, but the combination of venue hours, referee and team "
                    + "rest rules left no free slot for: " + s.unplaced);
        }
        return out;
    }
}
