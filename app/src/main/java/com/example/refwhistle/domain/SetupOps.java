package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teams, venues and referees travel through the same log as the matches, so a phone that joins a
 * tournament receives the whole setup by itself. Plain Java so it can be unit-tested.
 *
 * Entries (the id of the team, venue or referee is stored in {@code Operation.matchId}):
 *   ADD_TEAM            data = name
 *   ADD_VENUE           data = name|outdoor(0/1)|openMin|closeMin|lat|lng
 *   ADD_REFEREE         data = name|teamId
 *   SET_VENUE_LOCATION  data = lat|lng
 *   REMOVE_TEAM / REMOVE_VENUE / REMOVE_REFEREE  (no data)
 * Adding an id again with new details is how an item is renamed or edited.
 * {@link #fold} applies them in stamp order, so every phone ends with the same lists whatever order
 * the entries arrived in.
 */
public class SetupOps {
    public static boolean isSetup(Operation.Type type) {
        return type == Operation.Type.ADD_TEAM || type == Operation.Type.ADD_VENUE
                || type == Operation.Type.ADD_REFEREE || type == Operation.Type.SET_VENUE_LOCATION
                || type == Operation.Type.REMOVE_TEAM || type == Operation.Type.REMOVE_VENUE
                || type == Operation.Type.REMOVE_REFEREE;
    }

    /** Names must not contain the separators used by the log format. */
    public static String clean(String s) {
        return s == null ? "" : s.replace('|', '/').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String teamData(String name) {
        return clean(name);
    }

    public static String venueData(String name, boolean outdoor, int open, int close, double lat, double lng) {
        return clean(name) + "|" + (outdoor ? 1 : 0) + "|" + open + "|" + close + "|" + lat + "|" + lng;
    }

    public static String refereeData(String name, String teamId) {
        return clean(name) + "|" + clean(teamId);
    }

    public static String locationData(double lat, double lng) {
        return lat + "|" + lng;
    }

    /** Applies every setup entry (any order, duplicates allowed) and adds the result to {@code into}. */
    public static void fold(List<Operation> ops, Tournament into) {
        List<Operation> sorted = new ArrayList<Operation>();
        for (Operation op : ops) {
            if (isSetup(op.type)) sorted.add(op);
        }
        Collections.sort(sorted, new java.util.Comparator<Operation>() {
            public int compare(Operation a, Operation b) {
                return a.stamp.compareTo(b.stamp);
            }
        });

        Map<String, Team> teams = new LinkedHashMap<String, Team>();
        Map<String, Venue> venues = new LinkedHashMap<String, Venue>();
        Map<String, Referee> referees = new LinkedHashMap<String, Referee>();
        for (Operation op : sorted) {
            try {
                if (op.type == Operation.Type.ADD_TEAM) {
                    teams.put(op.matchId, new Team(op.matchId, op.data == null ? op.matchId : op.data));
                } else if (op.type == Operation.Type.ADD_VENUE) {
                    String[] p = op.data.split("\\|", -1);
                    venues.put(op.matchId, new Venue(op.matchId, p[0], p[1].equals("1"),
                            Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Double.parseDouble(p[4]), Double.parseDouble(p[5])));
                } else if (op.type == Operation.Type.ADD_REFEREE) {
                    String[] p = op.data.split("\\|", -1);
                    referees.put(op.matchId, new Referee(op.matchId, p[0], p.length > 1 ? p[1] : ""));
                } else if (op.type == Operation.Type.REMOVE_TEAM) {
                    teams.remove(op.matchId);
                } else if (op.type == Operation.Type.REMOVE_VENUE) {
                    venues.remove(op.matchId);
                } else if (op.type == Operation.Type.REMOVE_REFEREE) {
                    referees.remove(op.matchId);
                }
            } catch (RuntimeException unreadable) {
                // an entry from another app version: skip it, keep the rest
            }
        }
        // a location can only change a venue that exists, so apply these once all venues are known
        for (Operation op : sorted) {
            if (op.type != Operation.Type.SET_VENUE_LOCATION) continue;
            Venue v = venues.get(op.matchId);
            if (v == null || op.data == null) continue;
            try {
                String[] p = op.data.split("\\|", -1);
                venues.put(v.id, new Venue(v.id, v.name, v.outdoor, v.openMin, v.closeMin,
                        Double.parseDouble(p[0]), Double.parseDouble(p[1])));
            } catch (RuntimeException unreadable) {
                // skip
            }
        }
        into.teams.addAll(teams.values());
        into.venues.addAll(venues.values());
        into.referees.addAll(referees.values());
    }
}
