package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Text form of an operation, one per line, used for phone-to-phone sync and file export.
 * Format (tab separated): physical, counter, device, TYPE, matchId, matchLine, data.
 * Fields never contain tabs or newlines (ids are generated).
 */
public class LogCodec {
    public static String matchLine(Match m) {
        if (m == null) return "";
        return m.id + "|" + m.teamA + "|" + m.teamB + "|" + m.venueId + "|" + m.refereeId
                + "|" + m.day + "|" + m.startMin + "|" + m.durationMin;
    }

    public static Match parseMatch(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|");
        return new Match(p[0], p[1], p[2], p[3], p[4],
                Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]));
    }

    public static String encode(Operation op) {
        return op.stamp.physical + "\t" + op.stamp.counter + "\t" + op.stamp.deviceId + "\t"
                + op.type.name() + "\t" + op.matchId + "\t" + matchLine(op.payload) + "\t"
                + (op.data == null ? "" : op.data);
    }

    public static Operation decode(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 7) throw new IllegalArgumentException("bad op line: " + line);
        return new Operation(new Stamp(Long.parseLong(p[0]), Integer.parseInt(p[1]), p[2]),
                Operation.Type.valueOf(p[3]), p[4], parseMatch(p[5]), p[6].isEmpty() ? null : p[6]);
    }

    public static String encodeAll(List<Operation> ops) {
        StringBuilder sb = new StringBuilder();
        for (Operation op : ops) sb.append(encode(op)).append('\n');
        return sb.toString();
    }

    public static List<Operation> decodeAll(String text) {
        List<Operation> out = new ArrayList<Operation>();
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                out.add(decode(line.replace("\r", "")));
            } catch (RuntimeException unreadable) {
                // an entry from a different app version: skip it, keep the rest
            }
        }
        return out;
    }
}
