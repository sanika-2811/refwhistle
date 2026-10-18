package com.example.refwhistle.domain;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Tamper-evident ledger. Every operation (in the global sorted order) is folded into a SHA-256
 * hash chain: h[i] = SHA256(h[i-1] + encoded operation). The last hash (the "head") is a
 * fingerprint of the entire history:
 *  - two phones that show the same head hold exactly the same log (a visible proof of sync);
 *  - changing, removing or inserting any past entry changes every hash after it.
 */
public class Ledger {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Hash chain over the sorted log; element i is the hash after operation i. */
    public static List<String> chain(List<Operation> sortedOps) {
        List<String> out = new ArrayList<String>();
        String prev = "GENESIS";
        for (Operation op : sortedOps) {
            prev = sha256(prev + "\n" + LogCodec.encode(op));
            out.add(prev);
        }
        return out;
    }

    /** Head of the chain, or "GENESIS" for an empty log. */
    public static String head(List<Operation> sortedOps) {
        List<String> c = chain(sortedOps);
        return c.isEmpty() ? "GENESIS" : c.get(c.size() - 1);
    }

    /** Short form for the UI, e.g. "a3f9c21e". */
    public static String shortHead(List<Operation> sortedOps) {
        String h = head(sortedOps);
        return h.length() > 8 ? h.substring(0, 8) : h;
    }

    /** Index of the first operation whose hash differs from the trusted chain, or -1 if equal. */
    public static int firstMismatch(List<Operation> sortedOps, List<String> trustedChain) {
        List<String> now = chain(sortedOps);
        int n = Math.max(now.size(), trustedChain.size());
        for (int i = 0; i < n; i++) {
            if (i >= now.size() || i >= trustedChain.size() || !now.get(i).equals(trustedChain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
