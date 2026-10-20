package com.example.refwhistle.domain;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.List;

/**
 * The admin password. Its hash (never the password) is one entry in the log, so every phone of the
 * tournament can check a typed password. The FIRST entry (earliest stamp) is the real one: whoever
 * set it first owns the tournament, and a later entry from someone else changes nothing.
 *
 * This keeps referees and team members out of the admin screens in the app. It is not a server-side
 * lock: someone who rebuilds the app or guesses a weak password can still get in.
 */
public class AdminLock {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int ROUNDS = 20000;     // slows down guessing; a phone does this in a blink

    public static String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = ("refwhistle-admin:" + password).getBytes(UTF8);
            for (int i = 0; i < ROUNDS; i++) h = md.digest(h);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hash of the admin password in this log, or null if nobody has set one yet. */
    public static String passwordHash(List<Operation> ops) {
        Operation first = null;
        for (Operation op : ops) {
            if (op.type != Operation.Type.SET_ADMIN_PIN || op.data == null || op.data.isEmpty()) continue;
            if (first == null || op.stamp.compareTo(first.stamp) < 0) first = op;
        }
        return first == null ? null : first.data;
    }
}
