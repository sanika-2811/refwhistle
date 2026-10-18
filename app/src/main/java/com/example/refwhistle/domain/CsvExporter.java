package com.example.refwhistle.domain;

import java.util.Collection;
import java.util.Map;

/** Schedule to CSV text (opens in Excel / Sheets). */
public class CsvExporter {
    public static String schedule(Collection<Match> matches, Tournament t, Map<String, int[]> results) {
        StringBuilder sb = new StringBuilder("Day,Start,End,Match,Team A,Team B,Venue,Referee,Score\n");
        for (Match m : matches) {
            int[] r = results == null ? null : results.get(m.id);
            sb.append(m.day + 1).append(',')
                    .append(DelayPropagator.fmt(m.startMin)).append(',')
                    .append(DelayPropagator.fmt(m.endMin())).append(',')
                    .append(q(m.id)).append(',')
                    .append(q(t.teamName(m.teamA))).append(',')
                    .append(q(t.teamName(m.teamB))).append(',')
                    .append(q(t.venueName(m.venueId))).append(',')
                    .append(q(t.refereeName(m.refereeId))).append(',')
                    .append(r == null ? "" : r[0] + "-" + r[1]).append('\n');
        }
        return sb.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
