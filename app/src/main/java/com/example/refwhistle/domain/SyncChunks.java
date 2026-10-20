package com.example.refwhistle.domain;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a log (text, one entry per line) into messages small enough for Bluetooth/Nearby payloads
 * and puts them back together on the other phone. Plain Java so it can be unit-tested.
 *
 * Message format: "CHUNK i n\n" followed by whole log lines. Messages may arrive in any order and
 * more than once; the assembler hands back the full text once every part has arrived.
 */
public class SyncChunks {
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    public static List<String> split(String text, int maxBytes) {
        List<String> bodies = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            int b = line.getBytes(UTF8).length + 1;
            if (curBytes > 0 && curBytes + b > maxBytes - 32) {
                bodies.add(cur.toString());
                cur = new StringBuilder();
                curBytes = 0;
            }
            cur.append(line).append('\n');
            curBytes += b;
        }
        bodies.add(cur.toString());                 // at least one message, even for an empty log
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < bodies.size(); i++) {
            out.add("CHUNK " + (i + 1) + " " + bodies.size() + "\n" + bodies.get(i));
        }
        return out;
    }

    /** Collects the messages of one transfer. */
    public static class Assembler {
        private String[] parts;
        private int got;

        /** Returns the full text when the last part has arrived, otherwise null. */
        public String add(String message) {
            int nl = message.indexOf('\n');
            if (nl < 0 || !message.startsWith("CHUNK ")) return null;
            String[] h = message.substring(0, nl).split(" ");
            if (h.length != 3) return null;
            int i, n;
            try {
                i = Integer.parseInt(h[1]);
                n = Integer.parseInt(h[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 1 || i < 1 || i > n) return null;
            if (parts == null || parts.length != n) {
                parts = new String[n];
                got = 0;
            }
            if (parts[i - 1] == null) got++;
            parts[i - 1] = message.substring(nl + 1);
            if (got < n) return null;
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append(p);
            parts = null;
            got = 0;
            return sb.toString();
        }
    }
}
