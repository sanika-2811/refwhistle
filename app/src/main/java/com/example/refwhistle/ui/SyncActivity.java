package com.example.refwhistle.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.sync.NearbySync;
import com.example.refwhistle.sync.SyncManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Phones sync by themselves. The only thing to do is to type the same tournament code on every phone,
 * once. This screen shows what is happening and has a "Sync now" button and a file fallback.
 */
public class SyncActivity extends AppCompatActivity implements SyncManager.Listener {
    private SyncManager sync;
    private DBHelper db;
    private TextView tvState, tvInternet, tvNearby, tvEntries, tvMessage;
    private TextInputEditText etCode;
    private SwitchCompat swAuto;
    private boolean binding;

    private final ActivityResultLauncher<String[]> importer =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), new ActivityResultCallback<Uri>() {
                public void onActivityResult(Uri uri) {
                    if (uri != null) importFrom(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sync = SyncManager.get(this);
        db = new DBHelper(this, getSharedPreferences("refwhistle", MODE_PRIVATE).getString("device_id", "dev-unknown"));

        LinearLayout body = UiKit.screen(this, "Sync",
                "Phones update each other automatically, online or offline.");

        LinearLayout status = UiKit.card(this, body);
        tvState = UiKit.text(this, status, "", 22, true);
        tvInternet = UiKit.text(this, status, "", 13, false);
        tvNearby = UiKit.text(this, status, "", 13, false);
        tvEntries = UiKit.text(this, status, "", 13, false);
        tvEntries.setAlpha(0.7f);
        MaterialButton now = button("Sync now");
        now.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askPermissions();
                sync.syncNow();
                tvMessage.setText("Looking for changes on the internet and on phones close by...");
            }
        });
        status.addView(now);

        LinearLayout setup = UiKit.card(this, body);
        UiKit.text(this, setup, "Tournament code", 16, true);
        UiKit.text(this, setup, "The admin makes a code and shares it (for example on WhatsApp). Everyone types it once. "
                + "After that nothing else is needed: no addresses, no same Wi-Fi.", 13, false);
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("For example COLLEGE2026");
        etCode = new TextInputEditText(til.getContext());
        etCode.setText(sync.code());
        til.addView(etCode);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = UiKit.dp(this, 10);
        setup.addView(til, tlp);
        MaterialButton save = button("Save code and start");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String c = SyncManager.cleanCode(String.valueOf(etCode.getText()));
                if (c.isEmpty()) {
                    tvMessage.setText("Type a code first (letters and digits).");
                    return;
                }
                etCode.setText(c);
                askPermissions();
                sync.setEnabled(true);
                sync.setCode(c);
                tvMessage.setText("Code saved. This phone now syncs by itself.");
            }
        });
        setup.addView(save);

        MaterialButton gen = button("Make a new secure code");
        gen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                etCode.setText(newCode());
                tvMessage.setText("New code made. Tap Save, then share it with the other admins.");
            }
        });
        setup.addView(gen);

        MaterialButton share = button("Share code with others");
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String c = sync.code();
                if (c.isEmpty()) {
                    tvMessage.setText("Save a code first, then share it.");
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, "Join my RefWhistle tournament. Open the app, sign in, go to Sync "
                        + "and type this code: " + c);
                startActivity(Intent.createChooser(send, "Share code"));
            }
        });
        setup.addView(share);

        swAuto = new SwitchCompat(this);
        swAuto.setText("Sync automatically");
        swAuto.setChecked(sync.enabled());
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (on) askPermissions();
                sync.setEnabled(on);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = UiKit.dp(this, 10);
        setup.addView(swAuto, slp);

        LinearLayout how = UiKit.card(this, body);
        UiKit.text(this, how, "How it works", 16, true);
        UiKit.text(this, how, "• Online: changes go through the internet, so phones can be in different cities.", 13, false);
        UiKit.text(this, how, "• No internet: phones close to each other (about 10 metres) swap changes over Bluetooth. Keep Bluetooth on.", 13, false);
        UiKit.text(this, how, "• Both together: a change can reach every phone by either route. Nothing is lost if a phone was offline.", 13, false);

        LinearLayout file = UiKit.card(this, body);
        UiKit.text(this, file, "Last option: share a file", 16, true);
        UiKit.text(this, file, "If a phone has neither internet nor Bluetooth, send your changes through any app and import them on the other phone.", 13, false);
        MaterialButton export = button("Send my changes");
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, LogCodec.encodeAll(db.getOps()));
                startActivity(Intent.createChooser(send, "Send my changes"));
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

        LinearLayout out = UiKit.card(this, body);
        UiKit.text(this, out, "Message", 16, true);
        tvMessage = UiKit.text(this, out, "", 13, false);

        askPermissions();
        sync.start();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        sync.addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        sync.removeListener(this);
        super.onStop();
    }

    @Override
    public void onSyncState(boolean dataChanged) {
        render();
        if (dataChanged) tvMessage.setText("New changes arrived from another phone.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) {
            boolean all = grantResults.length > 0;
            for (int g : grantResults) all &= g == PackageManager.PERMISSION_GRANTED;
            if (all) sync.start();
            else tvMessage.setText("Without the Nearby permission, only internet sync works.");
        }
    }

    private void askPermissions() {
        if (!NearbySync.hasPermissions(this)) requestPermissions(NearbySync.neededPermissions(), 7);
    }

    private void render() {
        binding = true;
        swAuto.setChecked(sync.enabled());
        binding = false;
        if (sync.code().isEmpty()) {
            tvState.setText("Not set up yet");
            tvInternet.setText("Type a tournament code below to start.");
            tvNearby.setText("");
        } else {
            tvState.setText(sync.enabled() ? "Syncing automatically (" + sync.tile() + ")" : "Auto-sync is off");
            tvInternet.setText("Internet: " + sync.internetLine());
            String near = sync.nearbyLine();
            if (sync.nearbyPhonesSynced() > 0) near += "  (" + sync.nearbyPhonesSynced() + " exchanges so far)";
            tvNearby.setText("Phones close by: " + near);
        }
        String last = sync.lastReceivedAt() == 0 ? "no updates from other phones yet"
                : "last update from another phone " + DateUtils.getRelativeTimeSpanString(sync.lastReceivedAt());
        tvEntries.setText("Changes recorded: " + sync.changesRecorded() + "  •  " + last);
    }

    /** A long random code such as RW-K7Q2-X9M4-T5H8: nobody can guess it, and it avoids look-alike letters. */
    private static String newCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("RW");
        for (int g = 0; g < 3; g++) {
            sb.append('-');
            for (int i = 0; i < 4; i++) sb.append(letters.charAt(r.nextInt(letters.length())));
        }
        return sb.toString();
    }

    private MaterialButton button(String s) {
        MaterialButton b = new MaterialButton(this);
        b.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(this, 10);
        b.setLayoutParams(lp);
        return b;
    }

    private void importFrom(Uri uri) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            int n = db.mergeOps(LogCodec.decodeAll(sb.toString()));
            tvMessage.setText(n == 0 ? "Already up to date. Nothing new in that file."
                    : "Imported " + n + (n == 1 ? " new change." : " new changes."));
            sync.syncNow();                          // pass them on to the other phones too
            render();
        } catch (Exception e) {
            tvMessage.setText("Could not import that file: " + e.getMessage());
        }
    }
}
