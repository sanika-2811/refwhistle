package com.example.refwhistle.sync;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.SyncChunks;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.tasks.OnFailureListener;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phone-to-phone sync with no Wi-Fi network and no internet. Phones with the same tournament code
 * find each other over Bluetooth (Google Nearby Connections), connect by themselves and swap their
 * change logs. Merging is a set union, so doing it again, in any order, is always safe.
 *
 * Only phones that share the tournament code can see each other: the code is part of the service id.
 */
public class NearbySync {
    public interface Callback {
        void onStatus(String status);

        void onReceived(int added);
    }

    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final int MAX_PAYLOAD = 28000;           // Nearby byte payloads are limited to ~32 KB
    private static final long MIN_GAP_MS = 25000;           // do not reconnect to the same phone more often

    private final Context ctx;
    private final DBHelper db;
    private final String myName;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ConnectionsClient client;
    private boolean running;
    private final Map<String, String> names = new HashMap<String, String>();            // endpoint -> phone name
    private final Map<String, Long> lastSynced = new HashMap<String, Long>();
    private final Set<String> busy = new HashSet<String>();
    private final Map<String, SyncChunks.Assembler> inbox = new HashMap<String, SyncChunks.Assembler>();
    private int syncedPhones;

    public NearbySync(Context ctx, DBHelper db, String deviceId, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.db = db;
        this.myName = deviceId;
        this.cb = cb;
    }

    // ------------------------------------------------------------------ permissions

    public static String[] neededPermissions() {
        List<String> p = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_ADVERTISE");
            p.add("android.permission.BLUETOOTH_CONNECT");
            p.add("android.permission.BLUETOOTH_SCAN");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add("android.permission.NEARBY_WIFI_DEVICES");
        }
        if (Build.VERSION.SDK_INT <= 32) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return p.toArray(new String[0]);
    }

    public static boolean hasPermissions(Context c) {
        for (String perm : neededPermissions()) {
            if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ start / stop

    public void start(String tournamentCode) {
        if (running) return;
        if (!hasPermissions(ctx)) {
            cb.onStatus("Allow the Nearby permission to sync with phones close by");
            return;
        }
        try {
            client = Nearby.getConnectionsClient(ctx);
            String serviceId = "refwhistle." + tournamentCode.replaceAll("[^A-Za-z0-9_-]", "");
            running = true;
            client.startAdvertising(myName, serviceId, lifecycle,
                    new AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
                    .addOnFailureListener(new OnFailureListener() {
                        public void onFailure(Exception e) {
                            report("Nearby is not available: " + e.getMessage());
                        }
                    });
            client.startDiscovery(serviceId, discovery,
                    new DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
                    .addOnFailureListener(new OnFailureListener() {
                        public void onFailure(Exception e) {
                            report("Nearby is not available: " + e.getMessage());
                        }
                    });
            report("Looking for phones close by (keep Bluetooth on)");
        } catch (Throwable t) {
            running = false;
            report("Nearby is not available on this phone");
        }
    }

    public void stop() {
        if (client != null && running) {
            try {
                client.stopAdvertising();
                client.stopDiscovery();
                client.stopAllEndpoints();
            } catch (Throwable ignored) {
                // already stopped
            }
        }
        running = false;
        names.clear();
        busy.clear();
        inbox.clear();
        lastSynced.clear();
    }

    /** Try every phone we can see again (called when this phone has new changes, and every 30 s). */
    public void pushNow(boolean forceNow) {
        if (!running) return;
        if (forceNow) lastSynced.clear();
        for (String id : new ArrayList<String>(names.keySet())) connect(id, forceNow);
    }

    // ------------------------------------------------------------------ connecting

    private void connect(final String id, boolean anyone) {
        String theirName = names.get(id);
        if (!running || theirName == null || busy.contains(id)) return;
        Long last = lastSynced.get(id);
        if (last != null && System.currentTimeMillis() - last < MIN_GAP_MS) return;
        // Normally only the phone with the larger name starts the connection, so two phones do not
        // both try at once. A phone with new changes may start it too (anyone = true).
        if (!anyone && myName.compareTo(theirName) <= 0) return;
        busy.add(id);
        client.requestConnection(myName, id, lifecycle).addOnFailureListener(new OnFailureListener() {
            public void onFailure(Exception e) {
                busy.remove(id);
            }
        });
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
            names.put(id, info.getEndpointName());
            connect(id, false);
        }

        @Override
        public void onEndpointLost(String id) {
            names.remove(id);
            busy.remove(id);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String id, ConnectionInfo info) {
            names.put(id, info.getEndpointName());
            busy.add(id);
            client.acceptConnection(id, payloads);
        }

        @Override
        public void onConnectionResult(String id, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                report("Syncing with a nearby phone...");
                sendLog(id);
            } else {
                busy.remove(id);
            }
        }

        @Override
        public void onDisconnected(String id) {
            busy.remove(id);
            inbox.remove(id);
        }
    };

    // ------------------------------------------------------------------ exchanging the log

    private void sendLog(String id) {
        try {
            String text = LogCodec.encodeAll(db.getOps());
            for (String chunk : SyncChunks.split(text, MAX_PAYLOAD)) {
                client.sendPayload(id, Payload.fromBytes(chunk.getBytes(UTF8)));
            }
        } catch (Throwable t) {
            busy.remove(id);
        }
    }

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String id, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null) return;
            SyncChunks.Assembler a = inbox.get(id);
            if (a == null) {
                a = new SyncChunks.Assembler();
                inbox.put(id, a);
            }
            String full = a.add(new String(payload.asBytes(), UTF8));
            if (full == null) return;                              // more parts to come
            inbox.remove(id);
            List<Operation> incoming = LogCodec.decodeAll(full);
            int added = db.mergeOps(incoming);
            lastSynced.put(id, System.currentTimeMillis());
            syncedPhones++;
            report("Synced with a nearby phone");
            if (added > 0) cb.onReceived(added);
            final String endpoint = id;
            main.postDelayed(new Runnable() {
                public void run() {
                    try {
                        if (client != null) client.disconnectFromEndpoint(endpoint);
                    } catch (Throwable ignored) {
                        // already disconnected
                    }
                    busy.remove(endpoint);
                }
            }, 2000);
        }

        @Override
        public void onPayloadTransferUpdate(String id, PayloadTransferUpdate update) {
            // nothing to do: the receiving side merges once every part has arrived
        }
    };

    private void report(String status) {
        cb.onStatus(status);
    }

    public int syncedPhones() {
        return syncedPhones;
    }
}
