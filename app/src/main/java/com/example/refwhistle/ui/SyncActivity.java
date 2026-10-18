package com.example.refwhistle.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.net.PeerSync;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Offline-first sync. Works with no internet: both phones on one Wi-Fi/hotspot, one taps Host,
 * the other types its IP and taps Connect. Fallback: share the op-log as text and import it.
 */
public class SyncActivity extends AppCompatActivity {
    private DBHelper db;
    private TextView tvIp, tvHash, tvEntries, tvLog;
    private TextInputEditText etIp, etCode;

    private final ActivityResultLauncher<String[]> importer =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), new ActivityResultCallback<Uri>() {
                public void onActivityResult(Uri uri) {
                    if (uri != null) importFrom(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));

        LinearLayout body = UiKit.screen(this, "Sync",
                "Keep every phone in step, with or without internet.");

        LinearLayout status = UiKit.card(this, body);
        UiKit.text(this, status, "This phone's address on the network", 13, false).setAlpha(0.7f);
        tvIp = UiKit.text(this, status, "", 22, true);
        tvEntries = UiKit.text(this, status, "", 14, false);
        tvHash = UiKit.text(this, status, "", 14, false);
        tvHash.setTypeface(android.graphics.Typeface.MONOSPACE);
        UiKit.text(this, status, "After syncing, both phones should show the same sync code.", 12, false)
                .setAlpha(0.7f);

        LinearLayout net = UiKit.card(this, body);
        UiKit.text(this, net, "Sync over Wi-Fi or hotspot", 16, true);
        UiKit.text(this, net, "Put both phones on the same Wi-Fi or hotspot. One phone taps Wait. Every other phone types its address and taps Connect, one after another.", 13, false);
        MaterialButton host = button("Wait for other phones");
        host.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                log("Waiting for other phones to connect. Tell them this address: " + PeerSync.localIp()
                        + "\nIt keeps listening until 60 seconds after the last phone.");
                PeerSync.host(db, new PeerSync.HostCallback() {
                    public void onPeer(int peersSoFar, int newOps) {
                        log("Phone " + peersSoFar + " synced (" + newOps + (newOps == 1 ? " new change" : " new changes")
                                + " received). Waiting for more phones...");
                    }

                    public void onFinished(int peers, String error) {
                        if (error != null) log("Could not sync: " + error);
                        else if (peers == 0) log("No phone connected. Try again.");
                        else log("Done. " + peers + (peers == 1 ? " phone" : " phones") + " synced.");
                    }
                });
            }
        });
        net.addView(host);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Other phone's address (e.g. 192.168.43.1)");
        etIp = new TextInputEditText(til.getContext());
        til.addView(etIp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = UiKit.dp(this, 10);
        net.addView(til, tlp);

        MaterialButton connect = button("Connect and sync");
        connect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String ip = String.valueOf(etIp.getText()).trim();
                if (ip.isEmpty()) { log("Type the other phone's address first."); return; }
                log("Connecting to " + ip + " ...");
                PeerSync.connect(db, ip, callback());
            }
        });
        net.addView(connect);

        LinearLayout file = UiKit.card(this, body);
        UiKit.text(this, file, "No Wi-Fi? Share a file instead", 16, true);
        UiKit.text(this, file, "Send your changes through WhatsApp, Bluetooth or any app, then import them on the other phone.", 13, false);
        MaterialButton export = button("Send my changes");
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, LogCodec.encodeAll(db.getOps()));
                startActivity(Intent.createChooser(send, "Send op-log"));
            }
        });
        file.addView(export);
        MaterialButton imp = button("Import changes");
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                importer.launch(new String[]{"text/*", "application/octet-stream"});
            }
        });
        file.addView(imp);

        LinearLayout cloud = UiKit.card(this, body);
        UiKit.text(this, cloud, "Cloud backup", 16, true);
        if (!com.example.refwhistle.cloud.Cloud.available(this)) {
            UiKit.text(this, cloud, "Cloud backup is not available on this device.", 13, false)
                    .setAlpha(0.7f);
        } else {
            UiKit.text(this, cloud, "Everyone using the same tournament code shares one cloud backup. "
                    + "Nobody's changes are overwritten.", 13, false);
            TextInputLayout tc = new TextInputLayout(this);
            tc.setHint("Tournament code (e.g. COLLEGE2026)");
            etCode = new TextInputEditText(tc.getContext());
            etCode.setText(getSharedPreferences("refwhistle", Context.MODE_PRIVATE).getString("code", ""));
            tc.addView(etCode);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = UiKit.dp(this, 10);
            cloud.addView(tc, clp);
            MaterialButton up = button("Back up to cloud");
            up.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String code = code();
                    if (code == null) return;
                    log("Backing up...");
                    com.example.refwhistle.cloud.Cloud.upload(db, code, cloudDone(true));
                }
            });
            cloud.addView(up);
            MaterialButton down = button("Get updates from cloud");
            down.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String code = code();
                    if (code == null) return;
                    log("Checking the cloud...");
                    com.example.refwhistle.cloud.Cloud.download(db, code, cloudDone(false));
                }
            });
            cloud.addView(down);
        }

        LinearLayout out = UiKit.card(this, body);
        UiKit.text(this, out, "Status", 16, true);
        tvLog = UiKit.text(this, out, "Ready.", 13, false);
        refreshStatus();
    }

    private String code() {
        String c = String.valueOf(etCode.getText()).trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (c.isEmpty()) {
            log("Enter a tournament code first (letters and digits).");
            return null;
        }
        getSharedPreferences("refwhistle", Context.MODE_PRIVATE).edit().putString("code", c).apply();
        return c;
    }

    private com.example.refwhistle.cloud.Cloud.Done cloudDone(final boolean upload) {
        return new com.example.refwhistle.cloud.Cloud.Done() {
            public void onDone(int count, String error) {
                if (error != null) log("Cloud problem: " + error);
                else if (upload) log("Backed up " + count + (count == 1 ? " change." : " changes.") + " Your live standings are updated.");
                else log(count == 0 ? "Already up to date." : "Received " + count + (count == 1 ? " new change." : " new changes."));
            }
        };
    }

    private MaterialButton button(String s) {
        MaterialButton b = new MaterialButton(this);
        b.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(this, 10);
        b.setLayoutParams(lp);
        return b;
    }

    private PeerSync.Callback callback() {
        return new PeerSync.Callback() {
            public void onDone(int newOps, String error) {
                if (error != null) log("Could not sync: " + error);
                else log(newOps == 0 ? "Already up to date. Nothing new to receive."
                        : "Sync complete. " + newOps + (newOps == 1 ? " new change" : " new changes") + " received.");
            }
        };
    }

    private void importFrom(Uri uri) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            int n = db.mergeOps(LogCodec.decodeAll(sb.toString()));
            log(n == 0 ? "Already up to date. Nothing new in that file."
                    : "Imported " + n + (n == 1 ? " new change." : " new changes."));
        } catch (Exception e) {
            log("Could not import that file: " + e.getMessage());
        }
    }

    private void log(String s) {
        tvLog.setText(s);
        refreshStatus();
    }

    private void refreshStatus() {
        tvIp.setText(PeerSync.localIp());
        tvEntries.setText("Changes recorded: " + db.opLogSize());
        tvHash.setText("Sync code: " + db.ledgerHead());
    }
}
