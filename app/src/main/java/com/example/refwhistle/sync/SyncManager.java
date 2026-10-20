package com.example.refwhistle.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.example.refwhistle.cloud.Cloud;
import com.example.refwhistle.data.DBHelper;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every phone in step by itself. Two routes run side by side:
 *  - Internet: a shared cloud log that works from anywhere in the world (offline writes are queued).
 *  - Nearby:   phones close to each other swap logs over Bluetooth, with no Wi-Fi and no internet.
 * Both routes carry the same append-only log, so receiving an entry twice or in any order is harmless.
 * Call everything from the main thread.
 */
public class SyncManager {
    public interface Listener {
        /** @param dataChanged true when new entries from another phone were merged. */
        void onSyncState(boolean dataChanged);
    }

    private static final long HEARTBEAT_MS = 30000;

    @android.annotation.SuppressLint("StaticFieldLeak")   // holds the application context only
    private static SyncManager instance;

    public static synchronized SyncManager get(Context c) {
        if (instance == null) instance = new SyncManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final DBHelper db;
    private final String deviceId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Set<String> uploaded = new HashSet<String>();

    private NearbySync nearby;
    private ListenerRegistration cloudReg;
    private boolean started;
    private boolean cloudConnected;
    private boolean signingIn;
    private String cloudState = "";
    private String nearbyState = "";
    private long lastReceivedAt;

    private SyncManager(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("refwhistle", Context.MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = "dev-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("device_id", id).apply();
        }
        this.deviceId = id;
        this.db = new DBHelper(app, id);
    }

    // ------------------------------------------------------------------ settings

    public String code() {
        return prefs.getString("code", "");
    }

    public boolean enabled() {
        return prefs.getBoolean("auto_sync", true);
    }

    public static String cleanCode(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_-]", "");
    }

    public void setCode(String raw) {
        stop();
        prefs.edit().putString("code", cleanCode(raw)).apply();
        uploaded.clear();
        start();
    }

    public void setEnabled(boolean on) {
        prefs.edit().putBoolean("auto_sync", on).apply();
        if (on) start();
        else stop();
        notifyListeners(false);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners(boolean dataChanged) {
        for (Listener l : listeners) l.onSyncState(dataChanged);
    }

    // ------------------------------------------------------------------ start / stop

    /** Safe to call again and again (for example from onResume): it only starts what is not running. */
    public void start() {
        String code = code();
        if (code.isEmpty()) {
            cloudState = "";
            nearbyState = "";
            notifyListeners(false);
            return;
        }
        if (!enabled()) return;
        if (!started) {
            started = true;
            nearby = new NearbySync(app, db, deviceId, nearbyCallback);
            DBHelper.onLocalChange = new Runnable() {
                public void run() {
                    main.post(new Runnable() {
                        public void run() {
                            localChanged();
                        }
                    });
                }
            };
            main.postDelayed(heartbeat, HEARTBEAT_MS);
        }
        nearby.start(code);                       // no-op when already running or permission missing
        startCloud(code);
    }

    public void stop() {
        started = false;
        main.removeCallbacks(heartbeat);
        DBHelper.onLocalChange = null;
        if (nearby != null) nearby.stop();
        nearby = null;
        if (cloudReg != null) cloudReg.remove();
        cloudReg = null;
        cloudConnected = false;
        signingIn = false;
    }

    /** "Sync now" button: try every route right away. */
    public void syncNow() {
        if (!started) {
            start();
        }
        if (nearby != null) nearby.pushNow(true);
        if (cloudReg == null) startCloud(code());
        else pushCloud();
        notifyListeners(false);
    }

    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (!started) return;
            if (nearby != null) {
                nearby.start(code());
                nearby.pushNow(false);
            }
            if (cloudReg == null) startCloud(code());
            else pushCloud();
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ------------------------------------------------------------------ internet route

    private void startCloud(final String code) {
        if (!Cloud.available(app)) {
            cloudState = "Internet sync is not set up on this build";
            return;
        }
        if (cloudReg != null || signingIn) return;
        signingIn = true;
        Cloud.ensureSignedIn(app, new Cloud.Ready() {
            public void onReady(boolean ok, String error) {
                signingIn = false;
                if (!started) return;
                if (!ok) {
                    cloudConnected = false;
                    cloudState = "Not signed in. Open Account in the menu to sync over the internet.";
                    notifyListeners(false);
                    return;
                }
                cloudReg = Cloud.listen(db, code, uploaded, cloudCallback);
            }
        });
    }

    private final Cloud.Received cloudCallback = new Cloud.Received() {
        public void onReceived(int added) {
            lastReceivedAt = System.currentTimeMillis();
            if (nearby != null) nearby.pushNow(true);        // pass new changes on to phones close by
            notifyListeners(true);
        }

        public void onSnapshot(boolean fromServer) {
            cloudConnected = fromServer;
            cloudState = fromServer ? "Connected to the internet" : "No internet. Changes will be sent when it returns.";
            pushCloud();
            notifyListeners(false);
        }

        public void onError(String message) {
            cloudConnected = false;
            cloudState = "Internet sync problem: " + message;
            notifyListeners(false);
        }
    };

    private void pushCloud() {
        if (cloudReg == null) return;
        int queued = Cloud.uploadNew(db, code(), uploaded, new Cloud.Failure() {
            public void onFail(List<String> opIds, String error) {
                uploaded.removeAll(opIds);                   // try these again next time
                cloudState = "Could not send changes: " + error;
                notifyListeners(false);
            }
        });
        if (queued > 0 && Cloud.user(app) != null) Cloud.publishStandings(db, code());
    }

    // ------------------------------------------------------------------ nearby route

    private final NearbySync.Callback nearbyCallback = new NearbySync.Callback() {
        public void onStatus(String status) {
            nearbyState = status;
            notifyListeners(false);
        }

        public void onReceived(int added) {
            lastReceivedAt = System.currentTimeMillis();
            pushCloud();                                     // changes from a phone nearby also go to the cloud
            notifyListeners(true);
        }
    };

    // ------------------------------------------------------------------ local changes

    private void localChanged() {
        if (!started) return;
        pushCloud();
        if (nearby != null) nearby.pushNow(true);
        notifyListeners(false);
    }

    // ------------------------------------------------------------------ what the screens show

    /** One word for the tile on the schedule screen. */
    public String tile() {
        if (code().isEmpty()) return "Set up";
        if (!enabled()) return "Off";
        return cloudConnected ? "Online" : "Offline";
    }

    public String internetLine() {
        if (code().isEmpty()) return "";
        if (!enabled()) return "Auto-sync is off";
        return cloudState.isEmpty() ? "Connecting..." : cloudState;
    }

    public String nearbyLine() {
        if (code().isEmpty()) return "";
        if (!enabled()) return "Auto-sync is off";
        return nearbyState.isEmpty() ? "Starting..." : nearbyState;
    }

    public long lastReceivedAt() {
        return lastReceivedAt;
    }

    public int nearbyPhonesSynced() {
        return nearby == null ? 0 : nearby.syncedPhones();
    }

    public int changesRecorded() {
        return db.opLogSize();
    }
}
