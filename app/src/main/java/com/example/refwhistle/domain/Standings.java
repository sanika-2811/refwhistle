package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** League table from match results: 3 points for a win, 1 for a draw. */
public class Standings {
    public static class Row {
        public final String teamId;
        public final String name;
        public int played, won, drawn, lost, goalsFor, goalsAgainst, points;

        Row(String teamId, String name) {
            this.teamId = teamId;
            this.name = name;
        }

        public int goalDiff() {
            return goalsFor - goalsAgainst;
        }
    }

    public static List<Row> compute(Tournament t, Map<String, Match> matches, Map<String, int[]> results) {
        Map<String, Row> rows = new HashMap<String, Row>();
        for (Team team : t.teams) rows.put(team.id, new Row(team.id, team.name));
        for (Map.Entry<String, int[]> e : results.entrySet()) {
            Match m = matches.get(e.getKey());
            if (m == null) continue;
            Row a = rows.get(m.teamA), b = rows.get(m.teamB);
            if (a == null || b == null) continue;
            int sa = e.getValue()[0], sb = e.getValue()[1];
            a.played++; b.played++;
            a.goalsFor += sa; a.goalsAgainst += sb;
            b.goalsFor += sb; b.goalsAgainst += sa;
            if (sa > sb) { a.won++; b.lost++; a.points += 3; }
            else if (sa < sb) { b.won++; a.lost++; b.points += 3; }
            else { a.drawn++; b.drawn++; a.points++; b.points++; }
        }
        List<Row> list = new ArrayList<Row>(rows.values());
        Collections.sort(list, new Comparator<Row>() {
            public int compare(Row x, Row y) {
                if (x.points != y.points) return y.points - x.points;
                if (x.goalDiff() != y.goalDiff()) return y.goalDiff() - x.goalDiff();
                if (x.goalsFor != y.goalsFor) return y.goalsFor - x.goalsFor;
                return x.name.compareTo(y.name);
            }
        });
        return list;
    }

    /** JSON for the Flutter dashboard (hand-written so the domain stays dependency free). */
    public static String toJson(List<Row> rows) {
        StringBuilder sb = new StringBuilder("{\"standings\":[");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"team\":\"").append(r.name.replace("\"", "'")).append("\",\"p\":").append(r.played)
                    .append(",\"w\":").append(r.won).append(",\"d\":").append(r.drawn)
                    .append(",\"l\":").append(r.lost).append(",\"gf\":").append(r.goalsFor)
                    .append(",\"ga\":").append(r.goalsAgainst).append(",\"pts\":").append(r.points).append('}');
        }
        return sb.append("]}").toString();
    }
}
