package com.example.refwhistle.cloud;

import android.content.Context;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Standings;
import com.example.refwhistle.domain.Tournament;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional cloud layer. Everything here is a no-op unless app/google-services.json was added,
 * so the app keeps working fully offline without Firebase.
 *
 * Why not just write the schedule to Firestore? Firestore offline writes are last-write-wins per
 * document, which would silently drop one admin's change. So we store the append-only op-log
 * instead: ONE DOCUMENT PER OPERATION, id = the operation's unique stamp. Documents are never
 * overwritten with different content, so uploads from several admins just add up, and
 * every phone replays the union in the same order.
 */
public class Cloud {
    public interface Done {
        void onDone(int count, String error);
    }

    public static boolean available(Context c) {
        try {
            return !FirebaseApp.getApps(c).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    public static FirebaseUser user(Context c) {
        return available(c) ? FirebaseAuth.getInstance().getCurrentUser() : null;
    }

    public static void signOut(Context c) {
        if (available(c)) FirebaseAuth.getInstance().signOut();
    }

    private static CollectionReference ops(String tournamentCode) {
        return FirebaseFirestore.getInstance().collection("tournaments")
                .document(tournamentCode).collection("ops");
    }

    /** Upload every local operation (idempotent: same id, same content). */
    public static void upload(DBHelper db, String code, final Done cb) {
        List<Operation> all = db.getOps();
        CollectionReference col = ops(code);
        final int total = all.size();
        List<WriteBatch> batches = new ArrayList<WriteBatch>();
        WriteBatch cur = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 400 == 0) {
                cur = FirebaseFirestore.getInstance().batch();
                batches.add(cur);
            }
            Operation op = all.get(i);
            Map<String, Object> doc = new HashMap<String, Object>();
            doc.put("line", LogCodec.encode(op));
            doc.put("device", op.stamp.deviceId);
            cur.set(col.document(op.id()), doc);
        }
        publishStandings(db, code);
        if (batches.isEmpty()) {
            cb.onDone(0, null);
            return;
        }
        commitAll(batches, 0, total, cb);
    }

    /**
     * Publishes the current league table as one small document the Flutter dashboard polls:
     * tournaments/{code}/standings/latest  { json: "...", updated: millis }.
     */
    public static void publishStandings(DBHelper db, String code) {
        Tournament t = db.loadTournament();
        Replayer.Result st = db.state();
        String json = Standings.toJson(Standings.compute(t, st.matches, st.results));
        Map<String, Object> doc = new HashMap<String, Object>();
        doc.put("json", json);
        doc.put("updated", System.currentTimeMillis());
        FirebaseFirestore.getInstance().collection("tournaments").document(code)
                .collection("standings").document("latest").set(doc);
    }

    private static void commitAll(final List<WriteBatch> batches, final int index, final int total,
                                  final Done cb) {
        batches.get(index).commit().addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<Void>() {
            public void onComplete(com.google.android.gms.tasks.Task<Void> t) {
                if (!t.isSuccessful()) {
                    cb.onDone(0, String.valueOf(t.getException() == null ? "upload failed" : t.getException().getMessage()));
                } else if (index + 1 < batches.size()) {
                    commitAll(batches, index + 1, total, cb);
                } else {
                    cb.onDone(total, null);
                }
            }
        });
    }

    /** Download every operation in the cloud log and merge it into the local log. */
    public static void download(final DBHelper db, String code, final Done cb) {
        ops(code).get().addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<com.google.firebase.firestore.QuerySnapshot>() {
            public void onComplete(com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot> t) {
                if (!t.isSuccessful()) {
                    cb.onDone(0, String.valueOf(t.getException() == null ? "download failed" : t.getException().getMessage()));
                    return;
                }
                List<Operation> incoming = new ArrayList<Operation>();
                for (DocumentSnapshot d : t.getResult().getDocuments()) {
                    String line = d.getString("line");
                    if (line == null) continue;
                    try {
                        incoming.add(LogCodec.decode(line));
                    } catch (RuntimeException ignored) {
                        // skip malformed documents
                    }
                }
                cb.onDone(db.mergeOps(incoming), null);
            }
        });
    }
}
