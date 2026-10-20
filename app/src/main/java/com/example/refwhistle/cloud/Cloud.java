package com.example.refwhistle.cloud;

import android.content.Context;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Standings;
import com.example.refwhistle.domain.Tournament;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cloud layer used by the automatic sync. Everything here is a no-op unless the Firebase
 * configuration file was added, so the app keeps working fully offline without Firebase.
 *
 * The log is stored as ONE DOCUMENT PER ENTRY (id = the entry's unique stamp). A document is never
 * overwritten with different content, so uploads from several phones simply add up. Firestore keeps
 * writes made while offline and delivers them when the connection returns.
 */
public class Cloud {
    public interface Ready {
        void onReady(boolean ok, String error);
    }

    public interface Received {
        void onReceived(int added);

        /** After every update from the cloud; fromServer is false while only saved data is available (offline). */
        void onSnapshot(boolean fromServer);

        void onError(String message);
    }

    public interface Failure {
        void onFail(List<String> opIds, String error);
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

    /** The cloud route needs a signed-in user (the login screen guarantees one; it stays signed in offline). */
    public static void ensureSignedIn(Context c, final Ready cb) {
        if (!available(c)) {
            cb.onReady(false, "Cloud is not set up on this device");
        } else if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            cb.onReady(false, "Not signed in");   // offline mode: Bluetooth sync still works
        } else {
            cb.onReady(true, null);
        }
    }

    private static CollectionReference ops(String tournamentCode) {
        return FirebaseFirestore.getInstance().collection("tournaments")
                .document(tournamentCode).collection("ops");
    }

    /**
     * Sends the entries that were not sent yet. The ids are added to {@code uploaded} straight away;
     * if the cloud later refuses them, {@code onFail} gets the ids back so they are retried.
     *
     * @return how many entries were queued
     */
    public static int uploadNew(DBHelper db, String code, Set<String> uploaded, final Failure onFail) {
        CollectionReference col = ops(code);
        List<Operation> fresh = new ArrayList<Operation>();
        for (Operation op : db.getOps()) {
            if (!uploaded.contains(op.id())) fresh.add(op);
        }
        for (int from = 0; from < fresh.size(); from += 400) {
            WriteBatch batch = FirebaseFirestore.getInstance().batch();
            final List<String> ids = new ArrayList<String>();
            for (Operation op : fresh.subList(from, Math.min(fresh.size(), from + 400))) {
                Map<String, Object> doc = new HashMap<String, Object>();
                doc.put("line", LogCodec.encode(op));
                doc.put("device", op.stamp.deviceId);
                batch.set(col.document(op.id()), doc);
                ids.add(op.id());
                uploaded.add(op.id());
            }
            batch.commit().addOnCompleteListener(new OnCompleteListener<Void>() {
                public void onComplete(Task<Void> t) {
                    if (!t.isSuccessful()) {
                        onFail.onFail(ids, t.getException() == null ? "upload failed"
                                : String.valueOf(t.getException().getMessage()));
                    }
                }
            });
        }
        return fresh.size();
    }

    /** Live listener: every entry another phone adds is merged into the local log as it arrives. */
    public static ListenerRegistration listen(final DBHelper db, String code, final Set<String> uploaded,
                                              final Received cb) {
        return ops(code).addSnapshotListener(new EventListener<QuerySnapshot>() {
            public void onEvent(QuerySnapshot snap, FirebaseFirestoreException e) {
                if (e != null || snap == null) {
                    cb.onError(e == null ? "no data" : String.valueOf(e.getMessage()));
                    return;
                }
                List<Operation> incoming = new ArrayList<Operation>();
                for (DocumentChange ch : snap.getDocumentChanges()) {
                    if (ch.getType() != DocumentChange.Type.ADDED) continue;
                    String line = ch.getDocument().getString("line");
                    if (line == null) continue;
                    try {
                        Operation op = LogCodec.decode(line);
                        incoming.add(op);
                        uploaded.add(op.id());          // it is already in the cloud: never upload it again
                    } catch (RuntimeException ignored) {
                        // a document from a different app version: skip it
                    }
                }
                if (!incoming.isEmpty()) {
                    int added = db.mergeOps(incoming);
                    if (added > 0) cb.onReceived(added);
                }
                cb.onSnapshot(!snap.getMetadata().isFromCache());
            }
        });
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
}
